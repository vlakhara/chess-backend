package com.vlakhara.chess.entity;

import com.vlakhara.chess.enums.GameStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

public class Game {

    private String gameId;
    private Player white;
    private Player black;
    private Player winner;
    private Board board;
    private History history;
    private GameStatus status;
    private Player currentTurn;
    private Instant startedAt;
    private Instant endedAt;
    private boolean check;
    private boolean checkmate;
    private boolean stalemate;

    private WebSocketSession whiteSession;
    private WebSocketSession blackSession;

    public Game() {
    }

    public Game(String gameId, Player white, Player black, Player winner, Board board, History history, GameStatus status, Player currentTurn, Instant startedAt, Instant endedAt, boolean check, boolean checkmate, boolean stalemate, WebSocketSession whiteSession, WebSocketSession blackSession) {
        this.gameId = gameId;
        this.white = white;
        this.black = black;
        this.winner = winner;
        this.board = board;
        this.history = history;
        this.status = status;
        this.currentTurn = currentTurn;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.check = check;
        this.checkmate = checkmate;
        this.stalemate = stalemate;
        this.whiteSession = whiteSession;
        this.blackSession = blackSession;
    }

    public Game(String gameId, Player white, Player black, Board board, History history, GameStatus status, Player currentTurn, Instant startedAt, WebSocketSession whiteSession, WebSocketSession blackSession) {
        this.gameId = gameId;
        this.white = white;
        this.black = black;
        this.board = board;
        this.history = history;
        this.status = status;
        this.currentTurn = currentTurn;
        this.startedAt = startedAt;
        this.whiteSession = whiteSession;
        this.blackSession = blackSession;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public Player getWhite() {
        return white;
    }

    public void setWhite(Player white) {
        this.white = white;
    }

    public Player getBlack() {
        return black;
    }

    public void setBlack(Player black) {
        this.black = black;
    }

    public Player getWinner() {
        return winner;
    }

    public void setWinner(Player winner) {
        this.winner = winner;
    }

    public Board getBoard() {
        return board;
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    public History getHistory() {
        return history;
    }

    public void setHistory(History history) {
        this.history = history;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public Player getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(Player currentTurn) {
        this.currentTurn = currentTurn;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public WebSocketSession getWhiteSession() {
        return whiteSession;
    }

    public void setWhiteSession(WebSocketSession whiteSession) {
        this.whiteSession = whiteSession;
    }

    public WebSocketSession getBlackSession() {
        return blackSession;
    }

    public void setBlackSession(WebSocketSession blackSession) {
        this.blackSession = blackSession;
    }

    public boolean isCheck() {
        return check;
    }

    public void setCheck(boolean check) {
        this.check = check;
    }

    public boolean isCheckmate() {
        return checkmate;
    }

    public void setCheckmate(boolean checkmate) {
        this.checkmate = checkmate;
    }

    public boolean isStalemate() {
        return stalemate;
    }

    public void setStalemate(boolean stalemate) {
        this.stalemate = stalemate;
    }
}
