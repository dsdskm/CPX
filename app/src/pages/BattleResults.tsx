import React, { useEffect, useMemo, useState } from "react";
import { Card, Table, message, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { collection, onSnapshot, query } from "firebase/firestore";
import { db } from "../lib/firebase";

type GameState = "waiting" | "working" | "paused" | "completed" | string;

type ScoresMap = Record<string, number>;

type GameDoc = {
  id: string;
  state?: GameState;
  currentTeamId?: number | null;
  turnIndex?: number;
  order?: Array<{ teamId: number; teamName?: string; order?: number }>;
  initialScoresByTeamId?: ScoresMap;
  bonusByTeamId?: ScoresMap;
  damageTakenByTeamId?: ScoresMap;
  scoresByTeamId?: ScoresMap;
};

const STATE_LABEL: Record<string, string> = {
  waiting: "대기",
  working: "진행중",
  paused: "일시정지",
  completed: "종료",
};

const STATE_COLOR: Record<string, string> = {
  waiting: "default",
  working: "green",
  paused: "orange",
  completed: "purple",
};

function safeNum(v: unknown): number {
  if (typeof v === "number") return v;
  if (typeof v === "string") {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function normalizeScoresMap(raw: unknown): ScoresMap {
  const m = (raw ?? {}) as Record<string, unknown>;
  const out: ScoresMap = {};
  for (const [k, v] of Object.entries(m)) out[String(k)] = safeNum(v);
  return out;
}

function ScoreTable({ title, data }: { title: string; data: ScoresMap }) {
  const rows = Object.entries(data)
    .map(([teamId, score]) => ({ teamId, score }))
    .sort((a, b) => Number(a.teamId) - Number(b.teamId));

  if (rows.length === 0) return <span style={{ color: "#999" }}>-</span>;

  return (
    <div style={{ lineHeight: 1.3 }}>
      <div style={{ fontSize: 12, color: "#666", marginBottom: 6 }}>{title}</div>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <tbody>
          {rows.map((r) => (
            <tr key={r.teamId}>
              <td style={{ padding: "2px 0" }}>{r.teamId}팀</td>
              <td style={{ padding: "2px 0", textAlign: "right" }}>{r.score}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * ✅ 라운드 계산 (질문에서 준 Kotlin 로직 그대로)
 * - 전체 팀이 한 턴씩 돌면 1 라운드 증가
 * - turnIndex는 0부터 시작한다고 가정
 */
function calcRoundInfo(turnIndex: number, totalTeams: number) {
  const round = totalTeams <= 0 ? 0 : Math.floor(turnIndex / totalTeams) + 1;
  const posInRound = totalTeams <= 0 ? 0 : (turnIndex % totalTeams) + 1;
  const roundShown = round <= 0 ? 0 : Math.min(round, 10);

  return { round, posInRound, roundShown };
}

export default function BattleResults() {
  const [games, setGames] = useState<GameDoc[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);

    const q = query(collection(db, "games"));
    const unsub = onSnapshot(
      q,
      (snap) => {
        const list: GameDoc[] = snap.docs.map((d) => {
          const raw = d.data() as any;
          return {
            id: d.id,
            state: raw.state,
            currentTeamId: raw.currentTeamId ?? null,
            turnIndex: safeNum(raw.turnIndex),
            order: Array.isArray(raw.order) ? raw.order : [],
            initialScoresByTeamId: normalizeScoresMap(raw.initialScoresByTeamId),
            bonusByTeamId: normalizeScoresMap(raw.bonusByTeamId),
            damageTakenByTeamId: normalizeScoresMap(raw.damageTakenByTeamId),
            scoresByTeamId: normalizeScoresMap(raw.scoresByTeamId),
          };
        });

        // 보기 편하게: id(날짜형일 때) 내림차순
        list.sort((a, b) => String(b.id).localeCompare(String(a.id)));

        setGames(list);
        setLoading(false);
      },
      (err) => {
        console.error(err);
        message.error("games 목록을 불러오지 못했습니다.");
        setLoading(false);
      },
    );

    return () => unsub();
  }, []);

  const columns: ColumnsType<GameDoc> = useMemo(
    () => [
      {
        title: "게임ID",
        dataIndex: "id",
        width: 300,
        render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
      },
      {
        title: "게임 상태",
        dataIndex: "state",
        width: 120,
        render: (s?: GameState) => {
          const key = (s ?? "waiting").toString();
          return <Tag color={STATE_COLOR[key] ?? "default"}>{STATE_LABEL[key] ?? key}</Tag>;
        },
      },

      // ✅ 라운드 계산해서 표시
      {
        title: "라운드",
        key: "round",
        width: 160,
        render: (_: any, record) => {
          const turnIndex = record.turnIndex ?? 0;
          const totalTeams = record.order?.length ?? 0;

          const { roundShown, posInRound } = calcRoundInfo(turnIndex, totalTeams);

          if (totalTeams <= 0) return "-";

          // 예: 3/10 (2/5)  -> 3라운드 / 10, 이번 라운드 2번째 / 전체 5팀
          return `${roundShown}R / 10`;
        },
      },

      // ✅ 컬럼 위치 변경: "공격 순서" 먼저, 그 다음 "현재 턴"
      {
        title: "공격 순서",
        dataIndex: "order",
        width: 220,
        render: (_: any, record) => {
          const order = record.order ?? [];
          if (order.length === 0) return "-";

          const sorted = order
            .slice()
            .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
            .map((o) => `${o.teamId}팀`);

          return sorted.join(" → ");
        },
      },
      {
        title: "현재 턴",
        dataIndex: "currentTeamId",
        width: 120,
        render: (v?: number | null) => (v ? `${v}팀` : "-"),
      },

      {
        title: "최초 점수",
        dataIndex: "initialScoresByTeamId",
        width: 220,
        render: (_: any, r) => <ScoreTable title="최초" data={r.initialScoresByTeamId ?? {}} />,
      },
      {
        title: "획득 점수",
        dataIndex: "bonusByTeamId",
        width: 220,
        render: (_: any, r) => <ScoreTable title="획득" data={r.bonusByTeamId ?? {}} />,
      },
      {
        title: "피격 점수",
        dataIndex: "damageTakenByTeamId",
        width: 220,
        render: (_: any, r) => <ScoreTable title="피격" data={r.damageTakenByTeamId ?? {}} />,
      },
      {
        title: "현재 점수",
        dataIndex: "scoresByTeamId",
        width: 220,
        render: (_: any, r) => <ScoreTable title="현재" data={r.scoresByTeamId ?? {}} />,
      },
    ],
    [],
  );

  return (
    <Card title="전투 결과">
      <Table<GameDoc>
        rowKey={(r) => r.id}
        columns={columns}
        dataSource={games}
        loading={loading}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        scroll={{ x: 1400 }}
      />
    </Card>
  );
}
