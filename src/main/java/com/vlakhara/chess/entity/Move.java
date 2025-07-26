package com.vlakhara.chess.entity;


import com.vlakhara.chess.enums.PieceType;

public class Move {
    private Position from;
    private Position to;
    private Piece piece;
    private Piece capturedPiece;
    private Boolean isPromoted;
    private Boolean isCastle;
    private PieceType promotedTo;

    public Move() {
    }

    public Move(Position from, Position to, Piece piece, Piece capturedPiece, Boolean isPromoted, Boolean isCastle, PieceType promotedTo) {
        this.from = from;
        this.to = to;
        this.piece = piece;
        this.capturedPiece = capturedPiece;
        this.isPromoted = isPromoted;
        this.isCastle = isCastle;
        this.promotedTo = promotedTo;
    }

    public Position getFrom() {
        return from;
    }

    public void setFrom(Position from) {
        this.from = from;
    }

    public Position getTo() {
        return to;
    }

    public void setTo(Position to) {
        this.to = to;
    }

    public Piece getPiece() {
        return piece;
    }

    public void setPiece(Piece piece) {
        this.piece = piece;
    }

    public Piece getCapturedPiece() {
        return capturedPiece;
    }

    public void setCapturedPiece(Piece capturedPiece) {
        this.capturedPiece = capturedPiece;
    }

    public Boolean getPromoted() {
        return isPromoted;
    }

    public void setPromoted(Boolean promoted) {
        isPromoted = promoted;
    }

    public Boolean getCastle() {
        return isCastle;
    }

    public void setCastle(Boolean castle) {
        isCastle = castle;
    }

    public PieceType getPromotedTo() {
        return promotedTo;
    }

    public void setPromotedTo(PieceType promotedTo) {
        this.promotedTo = promotedTo;
    }
}
