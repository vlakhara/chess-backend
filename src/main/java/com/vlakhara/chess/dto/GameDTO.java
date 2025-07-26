package com.vlakhara.chess.dto;

import com.vlakhara.chess.entity.Board;
import com.vlakhara.chess.entity.Game;
import com.vlakhara.chess.entity.History;
import com.vlakhara.chess.entity.Player;

public class GameDTO {
    private String gameId;
    private Player white;
    private Player black;
    private Board board;
    private History history;
    private String status;
    private String startedAt;
    private Player currentTurn;
    private boolean check;
    private boolean checkmate;
    private boolean stalemate;
    private Player winner;

    public GameDTO() {
    }

    public GameDTO(Game game) {
        this.gameId = game.getGameId();
        this.white = game.getWhite();
        this.black = game.getBlack();
        this.board = game.getBoard();
        this.history = game.getHistory();
        this.status = game.getStatus().name();
        this.startedAt = game.getStartedAt().toString(); // or format it nicely
        this.currentTurn = game.getCurrentTurn();
        this.check = game.isCheck();
        this.checkmate = game.isCheckmate();
        this.stalemate = game.isStalemate();
        this.winner = game.getWinner();
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public Player getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(Player currentTurn) {
        this.currentTurn = currentTurn;
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

    public Player getWinner() {
        return winner;
    }

    public void setWinner(Player winner) {
        this.winner = winner;
    }
}
