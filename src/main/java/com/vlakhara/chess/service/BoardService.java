package com.vlakhara.chess.service;

import com.vlakhara.chess.entity.Board;
import com.vlakhara.chess.entity.Piece;
import com.vlakhara.chess.enums.PieceType;
import org.springframework.stereotype.Service;

@Service
public class BoardService {

    public Board initialiseBoard() {
        Piece[][] pieces = new Piece[8][8];
        
        // Initialize black pieces (top row)
        pieces[0][0] = new Piece(1, PieceType.ROOK, "black", false, "R");
        pieces[0][1] = new Piece(2, PieceType.KNIGHT, "black", false, "N");
        pieces[0][2] = new Piece(3, PieceType.BISHOP, "black", false, "B");
        pieces[0][3] = new Piece(4, PieceType.QUEEN, "black", false, "Q");
        pieces[0][4] = new Piece(5, PieceType.KING, "black", false, "K");
        pieces[0][5] = new Piece(6, PieceType.BISHOP, "black", false, "B");
        pieces[0][6] = new Piece(7, PieceType.KNIGHT, "black", false, "N");
        pieces[0][7] = new Piece(8, PieceType.ROOK, "black", false, "R");
        
        // Initialize black pawns
        for (int i = 0; i < 8; i++) {
            pieces[1][i] = new Piece(10 + i + 1, PieceType.PAWN, "black", false, "");
        }
        
        // Initialize empty middle rows
        for (int i = 2; i <= 5; i++) {
            for (int j = 0; j < 8; j++) {
                pieces[i][j] = null;
            }
        }
        
        // Initialize white pawns
        for (int i = 0; i < 8; i++) {
            pieces[6][i] = new Piece(60 + i + 1, PieceType.PAWN, "white", false, "");
        }
        
        // Initialize white pieces (bottom row)
        pieces[7][0] = new Piece(71, PieceType.ROOK, "white", false, "R");
        pieces[7][1] = new Piece(72, PieceType.KNIGHT, "white", false, "N");
        pieces[7][2] = new Piece(73, PieceType.BISHOP, "white", false, "B");
        pieces[7][3] = new Piece(74, PieceType.QUEEN, "white", false, "Q");
        pieces[7][4] = new Piece(75, PieceType.KING, "white", false, "K");
        pieces[7][5] = new Piece(76, PieceType.BISHOP, "white", false, "B");
        pieces[7][6] = new Piece(77, PieceType.KNIGHT, "white", false, "N");
        pieces[7][7] = new Piece(78, PieceType.ROOK, "white", false, "R");
        
        return new Board(pieces);
    }
}
