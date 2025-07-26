package com.vlakhara.chess.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class History {
    private final List<Move> moves = new ArrayList<>();
    private Move lastMove = new Move();

    public History() {}


    public History(List<Move> initialMoves) {
        if (initialMoves != null){
            this.moves.addAll(initialMoves);
            this.lastMove = initialMoves.get(initialMoves.size() - 1);
        }
    }

    public List<Move> getMoves() {
        return Collections.unmodifiableList(moves); // prevent external mutations
    }

    public void addMove(Move move) {
        moves.add(move);
    }

    public Move getLastMove() {
        return lastMove;
    }

    public void setLastMove(Move lastMove) {
        this.lastMove = lastMove;
    }
}

