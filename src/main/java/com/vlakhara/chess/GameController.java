package com.vlakhara.chess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vlakhara.chess.dto.GameDTO;
import com.vlakhara.chess.entity.*;
import com.vlakhara.chess.enums.GameStatus;
import com.vlakhara.chess.enums.GameType;
import com.vlakhara.chess.service.BoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameController extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Map<String, String> sessionsWithGame = new ConcurrentHashMap<>();

    @Autowired
    private BoardService boardService;

    public GameController() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Optional, human-readable
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            JsonNode typeNode = root.get("type");

            if (typeNode == null || !typeNode.isTextual()) {
                logger.warn("Invalid message format from session {}: missing or invalid 'type' field", session.getId());
                session.sendMessage(new TextMessage("❌ 'type' field is missing or invalid"));
                return;
            }

            String rawType = typeNode.asText();
            JsonNode payload = root.get("payload");

            if (payload == null || payload.isEmpty()) {
                logger.warn("Invalid message format from session {}: missing payload", session.getId());
                session.sendMessage(new TextMessage("❌ Payload is required"));
                return;
            }

            try {
                GameType type = GameType.valueOf(rawType.toUpperCase());
                switch (type) {
                    case MOVE:
                        move(session, payload);
                        break;
                    case PING:
                        // Heartbeat from the client: keeps the connection from
                        // going idle and lets the client measure round-trip time.
                        sendMessage(session, "PONG", Map.of("t", payload.path("t").asLong(0)));
                        break;
                    case CHAT:
                        logger.info("CHAT message received from session {}", session.getId());
                        break;
                    case RESIGN:
                        resign(session, payload);
                        break;
                    case DRAW_OFFER:
                        drawOffer(session, payload);
                        break;
                    case DRAW_RESPONSE:
                        drawResponse(session, payload);
                        break;
                    case LEAVE:
                        leave(session, payload);
                        break;
                    case JOIN:
                        join(session, payload);
                        break;
                    default:
                        logger.warn("Unknown game type: {} from session {}", rawType, session.getId());
                }
            } catch (IllegalArgumentException error) {
                logger.warn("Invalid game type: {} from session {}", rawType, session.getId());
                session.sendMessage(new TextMessage("❌ Unknown event type: " + rawType));
            }
        } catch (Exception e) {
            logger.error("Error handling message from session {}: {}", session.getId(), e.getMessage(), e);
            try {
                session.sendMessage(new TextMessage("❌ Internal server error"));
            } catch (IOException ioException) {
                logger.error("Failed to send error message to session {}", session.getId(), ioException);
            }
        }
    }

    private void join(WebSocketSession session, JsonNode payload) throws IOException {
        String gameId = payload.get("gameId").asText();
        String userName = payload.get("username").asText();

        if (gameId == null || gameId.isEmpty() || userName == null || userName.isEmpty()) {
            session.sendMessage(new TextMessage("❌ GameId and Username are required"));
            return;
        }

        Game game = findGameById(gameId);
        Player player = new Player(generateUniqueId(), userName, null);

        logger.info("Join attempt - GameId: {}, Username: {}, Existing game: {}", gameId, userName, game != null);
        
        if (game != null) {
            logger.info("Game details - White: {}, Black: {}, WhiteSession: {}, BlackSession: {}", 
                       game.getWhite() != null ? game.getWhite().getName() : "null",
                       game.getBlack() != null ? game.getBlack().getName() : "null",
                       game.getWhiteSession() != null ? "active" : "null",
                       game.getBlackSession() != null ? "active" : "null");
        }

        if (game == null) {
            // First player
            player.setColor("white");
            Board board = boardService.initialiseBoard();
            History history = new History();
            sessionsWithGame.put(session.getId(), gameId);

            game = new Game(
                    gameId,
                    player,
                    null,
                    board,
                    history,
                    GameStatus.WAITING_FOR_PLAYER,
                    player,
                    Instant.now(),
                    session,
                    null
            );

            games.put(gameId, game);
            logger.info("New game created: " + gameId + " with white player: " + userName);

            sendMessage(session, "SELF_JOIN", Map.of(
                    "game", new GameDTO(game),
                    "player", player
            ));

        } else if (game.getBlack() == null) {
            // Second player
            player.setColor("black");
            game.setBlack(player);
            game.setBlackSession(session);
            game.setStatus(GameStatus.IN_PROGRESS);
            sessionsWithGame.put(session.getId(), gameId);

            sendMessage(session, "SELF_JOIN", Map.of(
                    "game", new GameDTO(game),
                    "player", player
            ));

            sendMessage(game.getWhiteSession(), "OPPONENT_JOINED", Map.of(
                    "game", new GameDTO(game),
                    "player", game.getWhite()
            ));

            logger.info("Game " + gameId + " is now full with black player: " + userName);
            logGameState();
        } else {
            logger.warn("Game {} is already full. White: {}, Black: {}", 
                       gameId, 
                       game.getWhite() != null ? game.getWhite().getName() : "null",
                       game.getBlack() != null ? game.getBlack().getName() : "null");
            session.sendMessage(new TextMessage("❌ Game is already full"));
        }
    }

    /**
     * Relays a move to the opponent. The client sends only the move (a few
     * hundred bytes) and the opponent replays it, so the server does no heavy
     * (de)serialisation of the whole game on every move.
     */
    private void move(WebSocketSession session, JsonNode payload) throws IOException {
        if (payload.has("game")) {
            moveLegacy(payload);
            return;
        }

        Game game = findGameById(payload.path("gameId").asText(""));
        if (game == null) {
            logger.warn("MOVE for unknown game from session {}", session.getId());
            return;
        }

        boolean fromWhite = game.getWhiteSession() != null
                && session.getId().equals(game.getWhiteSession().getId());
        boolean fromBlack = game.getBlackSession() != null
                && session.getId().equals(game.getBlackSession().getId());
        if (!fromWhite && !fromBlack) {
            logger.warn("MOVE from a session that is not a player in game {}", game.getGameId());
            return;
        }

        // Remember how the game ended so a later disconnect is not treated as a resignation.
        if (payload.path("checkmate").asBoolean(false)) {
            game.setStatus(GameStatus.CHECKMATE);
            game.setWinner(fromWhite ? game.getWhite() : game.getBlack());
        } else if (payload.path("stalemate").asBoolean(false)) {
            game.setStatus(GameStatus.STALEMATE);
        }

        WebSocketSession opponent = fromWhite ? game.getBlackSession() : game.getWhiteSession();
        sendMessage(opponent, "OPPONENT_MOVE", payload);
    }

    /** Legacy protocol: the client sends the whole game. Kept for old clients. */
    private void moveLegacy(JsonNode payload) throws IOException {
        Game game = objectMapper.treeToValue(payload.get("game"), Game.class);
        Game _game = findGameById(game.getGameId());
        if (_game == null) {
            logger.warn("MOVE for unknown game {}", game.getGameId());
            return;
        }
        if(game.isStalemate()) {
            game.setStatus(GameStatus.STALEMATE);
        } else if (game.isCheckmate()) {
            game.setStatus(GameStatus.CHECKMATE);
        }

        // Keep the server's copy in step with the latest move. Without this the
        // stored game stayed at the starting position, so a later "opponent left"
        // message reset the winner's board.
        _game.setBoard(game.getBoard());
        _game.setHistory(game.getHistory());
        _game.setCurrentTurn(game.getCurrentTurn());
        _game.setCheck(game.isCheck());
        _game.setCheckmate(game.isCheckmate());
        _game.setStalemate(game.isStalemate());
        _game.setStatus(game.getStatus());
        _game.setWinner(game.getWinner());
        WebSocketSession session = game.getCurrentTurn().getColor().equals("white") ? _game.getWhiteSession() : _game.getBlackSession();
        sendMessage(session, "MOVED", Map.of(
                "game", game
        ));
    }

    // ---- Game flow: resign, draw and leave --------------------------------

    /** The game this session is playing in, or null if it is not a player in it. */
    private Game gameOf(WebSocketSession session, JsonNode payload) {
        Game game = findGameById(payload.path("gameId").asText(""));
        if (game == null) return null;
        return isWhite(game, session) || isBlack(game, session) ? game : null;
    }

    private boolean isWhite(Game game, WebSocketSession session) {
        return game.getWhiteSession() != null && session.getId().equals(game.getWhiteSession().getId());
    }

    private boolean isBlack(Game game, WebSocketSession session) {
        return game.getBlackSession() != null && session.getId().equals(game.getBlackSession().getId());
    }

    private WebSocketSession opponentSession(Game game, WebSocketSession session) {
        return isWhite(game, session) ? game.getBlackSession() : game.getWhiteSession();
    }

    private void resign(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) return;

        Player winner = isWhite(game, session) ? game.getBlack() : game.getWhite();
        game.setStatus(GameStatus.RESIGNED);
        game.setWinner(winner);
        logger.info("Game {} ended by resignation. Winner: {}", game.getGameId(), winner.getName());
        sendMessage(opponentSession(game, session), "OPPONENT_RESIGNED", Map.of("winner", winner.getColor()));
    }

    private void drawOffer(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) return;
        sendMessage(opponentSession(game, session), "DRAW_OFFERED", Map.of("offered", true));
    }

    private void drawResponse(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) return;

        boolean accepted = payload.path("accept").asBoolean(false);
        if (accepted) {
            game.setStatus(GameStatus.DRAW);
            logger.info("Game {} drawn by agreement", game.getGameId());
        }
        sendMessage(opponentSession(game, session), accepted ? "DRAW_ACCEPTED" : "DRAW_DECLINED", Map.of("ok", accepted));
    }

    /** A player leaves the game for good (Exit button or cancelling the wait). */
    private void leave(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null) return;

        boolean white = isWhite(game, session);
        WebSocketSession other = opponentSession(game, session);
        Player winner = white ? game.getBlack() : game.getWhite();

        sessionsWithGame.remove(session.getId());
        if (white) game.setWhiteSession(null);
        else game.setBlackSession(null);

        if (other == null || !other.isOpen()) {
            // Nobody left in the game (or nobody ever joined): drop it.
            games.remove(game.getGameId());
            if (other != null) sessionsWithGame.remove(other.getId());
            logger.info("Game {} removed after leave", game.getGameId());
            return;
        }

        // Leaving a game in progress counts as a resignation.
        if (game.getStatus() == GameStatus.IN_PROGRESS && winner != null) {
            game.setStatus(GameStatus.RESIGNED);
            game.setWinner(winner);
            sendMessage(other, "OPPONENT_LEFT", Map.of("winner", winner.getColor()));
        }
    }

    private Game findGameById(String id) {
        return games.get(id);
    }

    public boolean isGameAvailable(String gameId) {
        Game game = findGameById(gameId);
        if (game == null) {
            return true; // Game doesn't exist, so it's available
        }
        return game.getBlack() == null; // Game exists but black player hasn't joined
    }

    private String generateUniqueId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void sendMessage(WebSocketSession session, String type, Object payload) throws IOException {
        if (session != null && session.isOpen()) {
            SocketMessage message = new SocketMessage(type, payload);
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        }
    }

    private void logGameState() {
        logger.info("Current games state:");
        logger.info("Active games: " + games.size());
        logger.info("Active sessions: " + sessionsWithGame.size());
        games.forEach((gameId, game) -> {
            logger.info("Game " + gameId + ": " + 
                       "White session: " + (game.getWhiteSession() != null ? "active" : "null") + 
                       ", Black session: " + (game.getBlackSession() != null ? "active" : "null") +
                       ", Status: " + game.getStatus());
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        try {
            String sessionId = session.getId();
            logger.info("Client disconnected: {} with status: {} (code: {}, reason: {})", 
                       sessionId, status.getCode(), status.getCode(), status.getReason());
            
            String gameId = sessionsWithGame.remove(sessionId);
            logger.info("Game ID for disconnected session: {}", gameId);
            
            if (gameId == null) {
                logger.info("No game associated with session: {}", sessionId);
                return;
            }

            Game game = findGameById(gameId);
            if (game == null) {
                logger.info("Game not found for ID: {}", gameId);
                return;
            }

            boolean isWhite = game.getWhiteSession() != null && sessionId.equals(game.getWhiteSession().getId());
            boolean isBlack = game.getBlackSession() != null && sessionId.equals(game.getBlackSession().getId());

            if (!isWhite && !isBlack) {
                logger.info("Session not associated with any player in game: {}", gameId);
                return;
            }

            // Mark session as disconnected
            if (isWhite) {
                game.setWhiteSession(null);
                logger.info("White player disconnected from game: {}", gameId);
            }
            if (isBlack) {
                game.setBlackSession(null);
                logger.info("Black player disconnected from game: {}", gameId);
            }

            // Check if both players have disconnected
            if (game.getWhiteSession() == null && game.getBlackSession() == null) {
                logger.info("Both players disconnected, removing game: {}", gameId);
                logger.info("Before removal - Games count: {}", games.size());
                games.remove(gameId);
                logger.info("After removal - Games count: {}", games.size());
                
                // Clean up any remaining session references for this game
                final int[] removedSessions = {0};
                sessionsWithGame.entrySet().removeIf(entry -> {
                    boolean shouldRemove = gameId.equals(entry.getValue());
                    if (shouldRemove) removedSessions[0]++;
                    return shouldRemove;
                });
                logger.info("Removed {} session references for game: {}", removedSessions[0], gameId);
                
                // Log the current state after cleanup
                logGameState();
                return; // Exit early since game is removed
            }

            // If only one player disconnected and there's a winner, handle resignation
            Player winner = isWhite ? game.getBlack() : game.getWhite();
            WebSocketSession winnerSession = isWhite ? game.getBlackSession() : game.getWhiteSession();

            // Only if no winner has already been set and there's still an active opponent
            if (game.getWinner() == null && winner != null && winnerSession != null && winnerSession.isOpen()) {
                game.setStatus(GameStatus.RESIGNED);
                game.setWinner(winner);
                logger.info("Game ended by resignation. Winner: {}", winner.toString());

                try {
                    sendMessage(winnerSession, "OPPONENT_LEFT", Map.of("winner", winner.getColor()));
                    sessionsWithGame.remove(winnerSession.getId());
                } catch (IOException e) {
                    logger.error("Failed to send game end message to winner session: {}", winnerSession.getId(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling connection close for session: {}", session.getId(), e);
        }
    }
}
