package com.vlakhara.chess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vlakhara.chess.entity.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frontend sends castling/promotion flags as "isCastle" / "isPromoted".
 * These used to fail to deserialise, so those moves never reached the opponent.
 */
class MoveJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsCastleFlag() throws Exception {
        Move move = mapper.readValue(
                "{\"from\":{\"x\":7,\"y\":4},\"to\":{\"x\":7,\"y\":6},\"isCastle\":true}", Move.class);
        assertTrue(move.getCastle());
    }

    @Test
    void readsPromotionFlags() throws Exception {
        Move move = mapper.readValue(
                "{\"from\":{\"x\":1,\"y\":0},\"to\":{\"x\":0,\"y\":0},\"isPromoted\":true,\"promotedTo\":\"QUEEN\"}",
                Move.class);
        assertTrue(move.getPromoted());
        assertEquals("QUEEN", move.getPromotedTo().name());
    }

    @Test
    void writesFlagsUnderTheNamesTheFrontendExpects() throws Exception {
        Move move = new Move();
        move.setCastle(true);
        String json = mapper.writeValueAsString(move);
        assertTrue(json.contains("\"isCastle\":true"));
    }
}
