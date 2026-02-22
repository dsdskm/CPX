import React, { useEffect, useMemo, useState } from "react";
import { Card, Table, message, Tag, Typography, Button, Modal } from "antd";
import type { ColumnsType } from "antd/es/table";
import { DeleteOutlined } from "@ant-design/icons";
import { collection, onSnapshot, query, deleteDoc, doc } from "firebase/firestore";
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

/**
 * 20260222003315311 → 2026-02-22 00:33:15
 */
function formatGameIdAsDate(id: string): string {
  if (!/^\d{17}$/.test(id)) return id;

  const year = id.slice(0, 4);
  const month = id.slice(4, 6);
  const day = id.slice(6, 8);
  const hour = id.slice(8, 10);
  const minute = id.slice(10, 12);
  const second = id.slice(12, 14);

  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
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

function calcRoundInfo(turnIndex: number, totalTeams: number) {
  const round = totalTeams <= 0 ? 0 : Math.floor(turnIndex / totalTeams) + 1;
  const posInRound = totalTeams <= 0 ? 0 : (turnIndex % totalTeams) + 1;
  const roundShown = round <= 0 ? 0 : Math.min(round, 10);
  return { round, posInRound, roundShown };
}

export default function BattleResults() {
  const [games, setGames] = useState<GameDoc[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [deleting, setDeleting] = useState(false);

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

  const handleDelete = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning("삭제할 게임을 선택하세요.");
      return;
    }

    Modal.confirm({
      title: "선택한 게임을 삭제하시겠습니까?",
      content: `총 ${selectedRowKeys.length}개의 게임이 삭제됩니다.`,
      okText: "삭제",
      okType: "danger",
      cancelText: "취소",
      onOk: async () => {
        try {
          setDeleting(true);
          await Promise.all(
            selectedRowKeys.map((id) =>
              deleteDoc(doc(db, "games", String(id)))
            )
          );
          message.success("삭제가 완료되었습니다.");
          setSelectedRowKeys([]);
        } catch (err) {
          console.error(err);
          message.error("삭제 중 오류가 발생했습니다.");
        } finally {
          setDeleting(false);
        }
      },
    });
  };

  const columns: ColumnsType<GameDoc> = useMemo(
    () => [
      {
        title: "게임ID",
        dataIndex: "id",
        width: 300,
        render: (v: string) => (
          <Typography.Text code>
            {v === "default_game" ? v : formatGameIdAsDate(v)}
          </Typography.Text>
        ),
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
      {
        title: "라운드",
        key: "round",
        width: 160,
        render: (_: any, record) => {
          const turnIndex = record.turnIndex ?? 0;
          const totalTeams = record.order?.length ?? 0;
          const { roundShown } = calcRoundInfo(turnIndex, totalTeams);
          if (totalTeams <= 0) return "-";
          return `${roundShown}R / 10`;
        },
      },
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

  const rowSelection = {
    selectedRowKeys,
    onChange: (newSelectedRowKeys: React.Key[]) => {
      setSelectedRowKeys(newSelectedRowKeys);
    },
    getCheckboxProps: (record: GameDoc) => ({
      disabled: record.id === "default_game",
    }),
  };

  return (
    <Card
      title="전투 결과"
      extra={
        <Button
          danger
          icon={<DeleteOutlined />}
          disabled={selectedRowKeys.length === 0}
          loading={deleting}
          onClick={handleDelete}
        >
          선택 삭제
        </Button>
      }
    >
      <Table<GameDoc>
        rowKey={(r) => r.id}
        columns={columns}
        dataSource={games}
        loading={loading}
        rowSelection={rowSelection}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        scroll={{ x: 1400 }}
      />
    </Card>
  );
}