package com.vlakhara.chess.entity;

public class Board {
    private Piece[][] pieces;

    public Board() {
    }

    public Board(Piece[][] pieces) {
        this.pieces = deepCopy(pieces);
    }

    public Piece[][] getPieces() {
        return deepCopy(pieces); // prevent mutation
    }

    private Piece[][] deepCopy(Piece[][] original) {
        if (original == null) return null;
        Piece[][] copy = new Piece[8][8];
        for (int i = 0; i < 8; i++) {
            System.arraycopy(original[i], 0, copy[i], 0, 8);
        }
        return copy;
    }

    public void setPieces(Piece[][] pieces) {
        this.pieces = pieces;
    }
}
