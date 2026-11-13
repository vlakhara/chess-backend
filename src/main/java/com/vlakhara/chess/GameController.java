package com.vlakhara.chess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class GameController extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Map<String, String> sessionsWithGame = new ConcurrentHashMap<>();

    /** Per-game extras the Game entity does not carry: move log, clock, tokens, timers. */
    private final Map<String, GameState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chess-timers");
        t.setDaemon(true);
        return t;
    });
    /** How long a dropped player has to come back before forfeiting. */
    private final long graceMs = Long.getLong("chess.graceMs", 60_000L);

    static class GameState {
        String whiteToken;
        String blackToken;
        final List<JsonNode> moves = new ArrayList<>();
        long baseMs;
        long incMs;
        long whiteMs;
        long blackMs;
        long lastTick;
        boolean running;
        ScheduledFuture<?> flag;
        ScheduledFuture<?> graceWhite;
        ScheduledFuture<?> graceBlack;
        boolean whiteRematch;
        boolean blackRematch;

        boolean timed() { return baseMs > 0; }

        String turn() { return moves.size() % 2 == 0 ? "white" : "black"; }

        void resetClock() {
            whiteMs = baseMs;
            blackMs = baseMs;
            running = false;
            lastTick = 0;
            if (flag != null) { flag.cancel(false); flag = null; }
        }
    }

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
                    case REJOIN:
                        rejoin(session, payload);
                        break;
                    case REMATCH:
                        rematch(session, payload);
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

            GameState st = new GameState();
            st.whiteToken = newToken();
            JsonNode tc = payload.path("timeControl");
            st.baseMs = clamp(tc.path("base").asLong(0), 0, 7200) * 1000L;
            st.incMs = st.baseMs > 0 ? clamp(tc.path("inc").asLong(0), 0, 60) * 1000L : 0;
            st.resetClock();
            states.put(gameId, st);
            logger.info("New game created: " + gameId + " with white player: " + userName);

            sendMessage(session, "SELF_JOIN", joinPayload(game, st, player, st.whiteToken));

        } else if (game.getBlack() == null) {
            // Second player
            player.setColor("black");
            game.setBlack(player);
            game.setBlackSession(session);
            game.setStatus(GameStatus.IN_PROGRESS);
            sessionsWithGame.put(session.getId(), gameId);

            GameState st = states.get(gameId);
            st.blackToken = newToken();

            sendMessage(session, "SELF_JOIN", joinPayload(game, st, player, st.blackToken));
            sendMessage(game.getWhiteSession(), "OPPONENT_JOINED", joinPayload(game, st, game.getWhite(), null));

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

        GameState st = states.get(game.getGameId());
        if (st == null) return;

        synchronized (st) {
            if (game.getStatus() != GameStatus.IN_PROGRESS) return;
            String color = fromWhite ? "white" : "black";
            long now = System.currentTimeMillis();

            if (st.timed() && st.running) {
                long left = (fromWhite ? st.whiteMs : st.blackMs) - (now - st.lastTick);
                if (left <= 0) {
                    timeUp(game, st, color);
                    return;
                }
                if (fromWhite) st.whiteMs = left + st.incMs;
                else st.blackMs = left + st.incMs;
            }

            st.moves.add(payload.deepCopy());

            // Remember how the game ended so a later disconnect is not treated as a resignation.
            if (payload.path("checkmate").asBoolean(false)) {
                game.setStatus(GameStatus.CHECKMATE);
                game.setWinner(fromWhite ? game.getWhite() : game.getBlack());
                stopClock(st);
            } else if (payload.path("stalemate").asBoolean(false)) {
                game.setStatus(GameStatus.STALEMATE);
                stopClock(st);
            } else if (st.timed() && st.moves.size() >= 1) {
                // The clock starts with White's first move: from then on it is always
                // running for the side to move.
                st.running = true;
                st.lastTick = now;
                armFlag(game, st);
            }

            Map<String, Object> clock = clockJson(st);
            ObjectNode relay = payload.deepCopy();
            relay.set("clock", objectMapper.valueToTree(clock));
            sendMessage(session, "MOVE_ACK", Map.of("clock", clock));
            sendMessage(fromWhite ? game.getBlackSession() : game.getWhiteSession(), "OPPONENT_MOVE", relay);
        }
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
        stopClock(states.get(game.getGameId()));
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
            stopClock(states.get(game.getGameId()));
            logger.info("Game {} drawn by agreement", game.getGameId());
        }
        sendMessage(opponentSession(game, session), accepted ? "DRAW_ACCEPTED" : "DRAW_DECLINED", Map.of("ok", accepted));
    }

    /** A player leaves the game for good (Exit button or cancelling the wait). */
    private void leave(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null) return;
        GameState st = states.get(game.getGameId());

        boolean white = isWhite(game, session);
        WebSocketSession other = opponentSession(game, session);
        Player winner = white ? game.getBlack() : game.getWhite();

        sessionsWithGame.remove(session.getId());
        if (white) game.setWhiteSession(null);
        else game.setBlackSession(null);

        if (other == null || !other.isOpen()) {
            // Nobody left in the game (or nobody ever joined): drop it.
            if (other != null) sessionsWithGame.remove(other.getId());
            removeGame(game.getGameId());
            logger.info("Game {} removed after leave", game.getGameId());
            return;
        }

        if (st != null) {
            synchronized (st) {
                // Leaving a game in progress counts as a resignation.
                if (game.getStatus() == GameStatus.IN_PROGRESS && winner != null) {
                    game.setStatus(GameStatus.RESIGNED);
                    game.setWinner(winner);
                    stopClock(st);
                    sendMessage(other, "OPPONENT_LEFT", Map.of("winner", winner.getColor()));
                } else if (game.getStatus() != GameStatus.WAITING_FOR_PLAYER) {
                    sendMessage(other, "OPPONENT_GONE", Map.of("gone", true));
                }
            }
        }
    }

    // ---- Rejoin, rematch, clocks --------------------------------------------

    private void rejoin(WebSocketSession session, JsonNode payload) throws IOException {
        String gameId = payload.path("gameId").asText("");
        String token = payload.path("token").asText("");
        Game game = findGameById(gameId);
        GameState st = states.get(gameId);
        if (game == null || st == null || token.isEmpty()) {
            sendMessage(session, "REJOIN_FAILED", Map.of("gameId", gameId));
            return;
        }

        synchronized (st) {
            boolean white = token.equals(st.whiteToken);
            boolean black = !white && token.equals(st.blackToken) && game.getBlack() != null;
            if (!white && !black) {
                sendMessage(session, "REJOIN_FAILED", Map.of("gameId", gameId));
                return;
            }

            WebSocketSession old = white ? game.getWhiteSession() : game.getBlackSession();
            if (white) {
                game.setWhiteSession(session);
                if (st.graceWhite != null) { st.graceWhite.cancel(false); st.graceWhite = null; }
            } else {
                game.setBlackSession(session);
                if (st.graceBlack != null) { st.graceBlack.cancel(false); st.graceBlack = null; }
            }
            sessionsWithGame.put(session.getId(), gameId);
            if (old != null && old != session && old.isOpen()) {
                try { old.close(); } catch (IOException ignored) { }
            }

            Player me = white ? game.getWhite() : game.getBlack();
            WebSocketSession other = white ? game.getBlackSession() : game.getWhiteSession();
            boolean otherAway = game.getBlack() != null && (other == null || !other.isOpen());

            Map<String, Object> out = joinPayload(game, st, me, token);
            out.put("moves", st.moves);
            out.put("opponentAway", otherAway);
            out.put("winner", game.getWinner() != null ? game.getWinner().getColor() : null);
            out.put("rematchOffered", white ? st.blackRematch : st.whiteRematch);
            sendMessage(session, "REJOINED", out);
            if (!otherAway) sendMessage(other, "OPPONENT_BACK", Map.of("back", true));
            logger.info("Player rejoined game {} as {}", gameId, me.getColor());
        }
    }

    private void rematch(WebSocketSession session, JsonNode payload) throws IOException {
        Game game = gameOf(session, payload);
        if (game == null) return;
        GameState st = states.get(game.getGameId());
        if (st == null) return;

        synchronized (st) {
            GameStatus status = game.getStatus();
            if (game.getBlack() == null || status == GameStatus.IN_PROGRESS || status == GameStatus.WAITING_FOR_PLAYER) return;

            boolean white = isWhite(game, session);
            if (white) st.whiteRematch = true; else st.blackRematch = true;
            boolean otherWants = white ? st.blackRematch : st.whiteRematch;
            if (!otherWants) {
                sendMessage(opponentSession(game, session), "REMATCH_OFFERED", Map.of("offered", true));
                return;
            }

            // Both agreed: swap colours and start over.
            Player w = game.getWhite();
            Player b = game.getBlack();
            WebSocketSession ws = game.getWhiteSession();
            WebSocketSession bs = game.getBlackSession();
            String wt = st.whiteToken;
            st.whiteToken = st.blackToken;
            st.blackToken = wt;
            w.setColor("black");
            b.setColor("white");
            game.setWhite(b);
            game.setBlack(w);
            game.setWhiteSession(bs);
            game.setBlackSession(ws);

            game.setBoard(boardService.initialiseBoard());
            game.setHistory(new History());
            game.setStatus(GameStatus.IN_PROGRESS);
            game.setWinner(null);
            game.setCurrentTurn(b);
            game.setCheck(false);
            game.setCheckmate(false);
            game.setStalemate(false);
            game.setStartedAt(Instant.now());
            st.moves.clear();
            st.whiteRematch = false;
            st.blackRematch = false;
            st.resetClock();

            sendMessage(game.getWhiteSession(), "REMATCH_STARTED", joinPayload(game, st, game.getWhite(), st.whiteToken));
            sendMessage(game.getBlackSession(), "REMATCH_STARTED", joinPayload(game, st, game.getBlack(), st.blackToken));
            logger.info("Rematch started in game {}", game.getGameId());
        }
    }

    private Map<String, Object> joinPayload(Game game, GameState st, Player player, String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("game", new GameDTO(game));
        out.put("player", player);
        if (token != null) out.put("token", token);
        out.put("timeControl", Map.of("base", st.baseMs / 1000, "inc", st.incMs / 1000));
        out.put("clock", clockJson(st));
        return out;
    }

    /** Remaining time for each side as of now; the running side's time is already reduced. */
    private Map<String, Object> clockJson(GameState st) {
        long w = st.whiteMs;
        long b = st.blackMs;
        String turn = null;
        if (st.timed() && st.running) {
            long elapsed = System.currentTimeMillis() - st.lastTick;
            turn = st.turn();
            if (turn.equals("white")) w = Math.max(0, w - elapsed);
            else b = Math.max(0, b - elapsed);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whiteMs", w);
        out.put("blackMs", b);
        out.put("turn", turn);
        return out;
    }

    private void stopClock(GameState st) {
        if (st == null) return;
        synchronized (st) {
            st.running = false;
            if (st.flag != null) { st.flag.cancel(false); st.flag = null; }
        }
    }

    /** Schedules the moment the side to move runs out of time. */
    private void armFlag(Game game, GameState st) {
        if (st.flag != null) st.flag.cancel(false);
        final String color = st.turn();
        final int plies = st.moves.size();
        long left = color.equals("white") ? st.whiteMs : st.blackMs;
        st.flag = scheduler.schedule(() -> {
            synchronized (st) {
                if (game.getStatus() == GameStatus.IN_PROGRESS && st.running && st.moves.size() == plies) {
                    try { timeUp(game, st, color); } catch (IOException e) { logger.error("timeUp failed", e); }
                }
            }
        }, Math.max(left, 0) + 30, TimeUnit.MILLISECONDS);
    }

    private void timeUp(Game game, GameState st, String loserColor) throws IOException {
        Player winner = loserColor.equals("white") ? game.getBlack() : game.getWhite();
        game.setStatus(GameStatus.TIMEOUT);
        game.setWinner(winner);
        stopClock(st);
        if (loserColor.equals("white")) st.whiteMs = 0; else st.blackMs = 0;
        logger.info("Game {} ended on time. Winner: {}", game.getGameId(), winner.getColor());
        Map<String, Object> msg = Map.of("winner", winner.getColor(), "clock", clockJson(st));
        sendMessage(game.getWhiteSession(), "TIME_UP", msg);
        sendMessage(game.getBlackSession(), "TIME_UP", msg);
    }

    /** A player has been gone past the grace period. */
    private void graceExpired(String gameId, String color) {
        Game game = findGameById(gameId);
        GameState st = states.get(gameId);
        if (game == null || st == null) return;
        try {
            synchronized (st) {
                boolean white = color.equals("white");
                WebSocketSession mine = white ? game.getWhiteSession() : game.getBlackSession();
                if (mine != null) return; // they came back
                WebSocketSession other = white ? game.getBlackSession() : game.getWhiteSession();
                if (other == null || !other.isOpen()) {
                    removeGame(gameId);
                    return;
                }
                if (game.getStatus() == GameStatus.IN_PROGRESS) {
                    Player winner = white ? game.getBlack() : game.getWhite();
                    game.setStatus(GameStatus.RESIGNED);
                    game.setWinner(winner);
                    stopClock(st);
                    sendMessage(other, "OPPONENT_LEFT", Map.of("winner", winner.getColor()));
                } else {
                    sendMessage(other, "OPPONENT_GONE", Map.of("gone", true));
                }
            }
        } catch (IOException e) {
            logger.error("graceExpired failed for {}", gameId, e);
        }
    }

    private void removeGame(String gameId) {
        games.remove(gameId);
        GameState st = states.remove(gameId);
        if (st != null) {
            synchronized (st) {
                if (st.flag != null) st.flag.cancel(false);
                if (st.graceWhite != null) st.graceWhite.cancel(false);
                if (st.graceBlack != null) st.graceBlack.cancel(false);
            }
        }
        sessionsWithGame.entrySet().removeIf(e -> gameId.equals(e.getValue()));
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
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
            logger.info("Client disconnected: {} (code: {}, reason: {})", sessionId, status.getCode(), status.getReason());

            String gameId = sessionsWithGame.remove(sessionId);
            if (gameId == null) return;

            Game game = findGameById(gameId);
            GameState st = states.get(gameId);
            if (game == null || st == null) return;

            synchronized (st) {
                boolean white = isWhite(game, session);
                boolean black = isBlack(game, session);
                if (!white && !black) return; // an old connection that was already replaced

                String color = white ? "white" : "black";
                if (white) game.setWhiteSession(null);
                else game.setBlackSession(null);

                // Give the player a chance to come back (page refresh, network blip).
                ScheduledFuture<?> grace = scheduler.schedule(() -> graceExpired(gameId, color), graceMs, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> previous;
                if (white) { previous = st.graceWhite; st.graceWhite = grace; }
                else { previous = st.graceBlack; st.graceBlack = grace; }
                if (previous != null) previous.cancel(false);

                WebSocketSession other = white ? game.getBlackSession() : game.getWhiteSession();
                if (other != null && other.isOpen() && game.getStatus() == GameStatus.IN_PROGRESS) {
                    sendMessage(other, "OPPONENT_AWAY", Map.of("graceMs", graceMs));
                }
            }
        } catch (Exception e) {
            logger.error("Error handling connection close for session: {}", session.getId(), e);
        }
    }
}
