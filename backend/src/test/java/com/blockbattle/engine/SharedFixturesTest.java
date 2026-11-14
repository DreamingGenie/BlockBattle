package com.blockbattle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 공용 규칙 시험 데이터(shared/rules-fixtures)를 서버 Java 엔진으로 돌린다(CON-002).
 * 깨지면: 친구 대전 서버 재계산이 FE 엔진과 다른 보드·점수·공격을 내서 정상 플레이가 INVALID_LOCK을 받게 된다.
 */
class SharedFixturesTest {

    static final Path DIR = Path.of("..", "shared", "rules-fixtures");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode read(String name) throws IOException {
        return JSON.readTree(Files.readString(DIR.resolve(name)));
    }

    static Stream<JsonNode> bagCases() throws IOException {
        List<JsonNode> list = new ArrayList<>();
        read("bag.json").get("cases").forEach(list::add);
        return list.stream();
    }

    static Stream<JsonNode> lockCases() throws IOException {
        List<JsonNode> list = new ArrayList<>();
        read("lock-cases.json").get("cases").forEach(list::add);
        return list.stream();
    }

    @ParameterizedTest
    @MethodSource("bagCases")
    void 시드별_7bag_순서가_Python_참조_구현과_같다(JsonNode c) {
        PieceSequence seq = new PieceSequence(Long.parseLong(c.get("seed").asText()));
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < 21; i++) actual.add(seq.at(i).name());
        List<String> expected = new ArrayList<>();
        c.get("first21").forEach(n -> expected.add(n.asText()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 블록_모양이_srs_json과_같다() throws IOException {
        JsonNode shapes = read("srs.json").get("shapes");
        for (Piece piece : Piece.values()) {
            for (int r = 0; r < 4; r++) {
                int[][] cells = piece.cells(r);
                JsonNode expected = shapes.get(piece.name()).get(r);
                for (int i = 0; i < 4; i++) {
                    assertThat(cells[i]).containsExactly(expected.get(i).get(0).asInt(), expected.get(i).get(1).asInt());
                }
            }
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("lockCases")
    void lock_판정_점수_공격_상쇄가_FE와_같다(JsonNode c) {
        JsonNode s = c.get("state");
        JsonNode lock = c.get("lock");
        JsonNode e = c.get("expected");
        LockRules.State state = new LockRules.State(Board.fromBottomRows(rows(s.get("bottomRows"))),
                s.get("linesTotal").asInt(), s.get("combo").asInt(), s.get("b2b").asBoolean(), bundles(s.get("pending")));
        Placement p = new Placement(Piece.valueOf(lock.get("piece").asText()), lock.get("rotation").asInt(),
                lock.get("x").asInt(), lock.get("y").asInt());
        LockRules.Result r = LockRules.resolve(state, p, "rotate".equals(lock.get("lastAction").asText()));

        int dropScore = lock.path("softDropCells").asInt(0) + 2 * lock.path("hardDropCells").asInt(0);
        assertThat(r.board().toString()).as(c.get("name").asText())
                .isEqualTo(Board.fromBottomRows(rows(e.get("bottomRows"))).toString());
        assertThat(r.linesCleared()).isEqualTo(e.get("linesCleared").asInt());
        assertThat(r.tSpin()).isEqualTo(e.get("tSpin").asBoolean());
        assertThat(r.perfectClear()).isEqualTo(e.get("perfectClear").asBoolean());
        assertThat(r.backToBack()).isEqualTo(e.get("backToBack").asBoolean());
        // LockRules는 드롭 점수를 빼고 계산한다(드롭 점수는 LockService가 lock의 드롭 칸 수로 더한다, POL-005)
        assertThat(r.score()).isEqualTo(e.get("score").asInt() - dropScore);
        assertThat(r.level()).isEqualTo(e.get("level").asInt());
        assertThat(r.attack()).isEqualTo(e.get("attack").asInt());
        assertThat(r.cancelled()).isEqualTo(e.get("cancelled").asInt());
        assertThat(r.sent()).isEqualTo(e.get("sent").asInt());
        assertThat(r.garbageRaised()).isEqualTo(e.get("garbageRaised").asInt());
        JsonNode next = e.get("state");
        assertThat(r.next().linesTotal()).isEqualTo(next.get("linesTotal").asInt());
        assertThat(r.next().combo()).isEqualTo(next.get("combo").asInt());
        assertThat(r.next().b2b()).isEqualTo(next.get("b2b").asBoolean());
        assertThat(r.next().pending()).isEqualTo(bundles(next.get("pending")));
    }

    static List<String> rows(JsonNode node) {
        List<String> rows = new ArrayList<>();
        node.forEach(n -> rows.add(n.asText()));
        return rows;
    }

    static List<GarbageBundle> bundles(JsonNode node) {
        List<GarbageBundle> list = new ArrayList<>();
        node.forEach(n -> list.add(new GarbageBundle(n.get("lines").asInt(), n.get("holeColumn").asInt())));
        return list;
    }
}
