package com.vlakhara.chess.entity;

public class PieceWithPosition {
    private Piece piece;
    private Position position;

    public PieceWithPosition() {
    }

    public PieceWithPosition(Piece piece, Position position) {
        this.piece = piece;
        this.position = position;
    }

    public Piece getPiece() {
        return piece;
    }

    public void setPiece(Piece piece) {
        this.piece = piece;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }
}
