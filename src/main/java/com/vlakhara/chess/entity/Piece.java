package com.vlakhara.chess.entity;

import com.vlakhara.chess.enums.PieceType;

public class Piece {
    private int id;
    private PieceType type;
    private String color;
    private Boolean hasMoved;
    private String notation;

    public Piece() {
    }

    public Piece(int id, PieceType type, String color, Boolean hasMoved, String notation) {
        this.id = id;
        this.type = type;
        this.color = color;
        this.hasMoved = hasMoved;
        this.notation = notation;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public PieceType getType() {
        return type;
    }

    public void setType(PieceType type) {
        this.type = type;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public Boolean isHasMoved() {
        return hasMoved;
    }

    public void setHasMoved(Boolean hasMoved) {
        this.hasMoved = hasMoved;
    }

    public String getNotation() {
        return notation;
    }

    public void setNotation(String notation) {
        this.notation = notation;
    }
}
