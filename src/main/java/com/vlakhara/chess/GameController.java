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
        JsonNode root = objectMapper.readTree(message.getPayload());
        JsonNode typeNode = root.get("type");

        if (typeNode == null || !typeNode.isTextual()) {
            session.sendMessage(new TextMessage("❌ 'type' field is missing or invalid"));
            return;
        }

        String rawType = typeNode.asText();
        JsonNode payload = root.get("payload");

        if (payload == null || payload.isEmpty()) {
            session.sendMessage(new TextMessage("❌ Payload is required"));
            return;
        }

        try {
            GameType type = GameType.valueOf(rawType.toUpperCase());
            switch (type) {
                case MOVE:
                    move(payload);
                    break;
                case CHAT:
                    System.out.println("CHAT");
                    break;
                case RESIGN:
                    System.out.println("RESIGN");
                    break;
                case JOIN:
                    join(session, payload);
                    break;
                default:
                    System.out.println("Invalid Type");
            }
        } catch (IllegalArgumentException error) {
            session.sendMessage(new TextMessage("❌ Unknown event type: " + rawType));
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

        System.out.println(games);
        } else {
            session.sendMessage(new TextMessage("❌ Game is already full"));
        }
    }

    private void move(JsonNode payload) throws IOException {
        Game game = objectMapper.treeToValue(payload.get("game"), Game.class);
        Game _game = findGameById(game.getGameId());
        if(game.isStalemate()) {
            game.setStatus(GameStatus.STALEMATE);
        } else if (game.isCheckmate()) {
            game.setStatus(GameStatus.CHECKMATE);
        }
        WebSocketSession session = game.getCurrentTurn().getColor().equals("white") ? _game.getWhiteSession() : _game.getBlackSession();
        sendMessage(session, "MOVED", Map.of(
                "game", game
        ));
    }

    private Game findGameById(String id) {
        return games.get(id);
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("Client disconnected: " + session.getId());

        String sessionId = session.getId();
        logger.info(sessionId);
        String gameId = sessionsWithGame.remove(sessionId);
        logger.info(gameId);
        if (gameId == null) return;

        Game game = findGameById(gameId);
        if (game == null) return;

        boolean isWhite = game.getWhiteSession() != null && sessionId.equals(game.getWhiteSession().getId());
        boolean isBlack = game.getBlackSession() != null && sessionId.equals(game.getBlackSession().getId());

        if (!isWhite && !isBlack) return;

        // Mark session as disconnected
        if (isWhite) game.setWhiteSession(null);
        if (isBlack) game.setBlackSession(null);

        // Determine opponent
        Player winner = isWhite ? game.getBlack() : game.getWhite();
        WebSocketSession winnerSession = isWhite ? game.getBlackSession() : game.getWhiteSession();

        // Only if no winner has already been set
        if (game.getWinner() == null && winner != null) {
            game.setStatus(GameStatus.RESIGNED);
            game.setWinner(winner);
            logger.info(winner.toString());

            if (winnerSession != null && winnerSession.isOpen()) {
                sendMessage(winnerSession, "MOVED", Map.of("game", new GameDTO(game)));
                sessionsWithGame.remove(winnerSession.getId());
            }
        }

        if (game.getWhiteSession() == null && game.getBlackSession() == null) {
            games.remove(gameId);
        }
    }
}
