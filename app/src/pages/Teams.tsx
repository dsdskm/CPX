import React, {
    useMemo,
    useState,
    useEffect,
    createContext,
    useContext,
    useRef,
} from "react";
import {
    Card,
    Table,
    Button,
    Space,
    Modal,
    Form,
    Input,
    InputNumber,
    message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { HolderOutlined } from "@ant-design/icons";

import {
    collection,
    doc,
    onSnapshot,
    orderBy,
    query,
    updateDoc,
    setDoc,
    getDoc,
    writeBatch,
} from "firebase/firestore";
import { db } from "../lib/firebase";
import type { Team } from "../types/model";

// ✅ DnD Kit
import {
    DndContext,
    PointerSensor,
    useSensor,
    useSensors,
    closestCenter,
    type DragEndEvent,
} from "@dnd-kit/core";
import {
    SortableContext,
    useSortable,
    verticalListSortingStrategy,
    arrayMove,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

type TeamFormValues = {
    id: number;
    name: string;
    password: string;
};

const TEAMS_COL = "teams";

/** =========================
 *  Drag handle context (버전 안전)
 *  ========================= */
type SortableHookReturn = ReturnType<typeof useSortable>;

type DragHandleCtx = {
    attributes: SortableHookReturn["attributes"];
    listeners: SortableHookReturn["listeners"]; // undefined 가능
    setActivatorNodeRef: SortableHookReturn["setActivatorNodeRef"];
};

const DragHandleContext = createContext<DragHandleCtx | null>(null);

function DragHandle() {
    const ctx = useContext(DragHandleContext);
    if (!ctx) return null;

    return (
        <span
            ref={ctx.setActivatorNodeRef}
            {...ctx.attributes}
            {...(ctx.listeners ?? {})}
            style={{
                cursor: "grab",
                padding: "4px 8px",
                display: "inline-flex",
                alignItems: "center",
                color: "#999",
            }}
            title="드래그해서 순서 변경"
        >
            <HolderOutlined />
        </span>
    );
}

/** ✅ antd Table row를 sortable row로 */
function SortableRow(
    props: React.HTMLAttributes<HTMLTableRowElement> & { "data-row-key": string }
) {
    const {
        attributes,
        listeners,
        setNodeRef,
        setActivatorNodeRef,
        transform,
        transition,
        isDragging,
    } = useSortable({ id: props["data-row-key"] });

    const style: React.CSSProperties = {
        ...props.style,
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.6 : 1,
    };

    return (
        <DragHandleContext.Provider
            value={{ attributes, listeners, setActivatorNodeRef }}
        >
            <tr {...props} ref={setNodeRef} style={style} />
        </DragHandleContext.Provider>
    );
}

export default function Teams() {
    const [teams, setTeams] = useState<Team[]>([]);
    const teamsRef = useRef<Team[]>([]);
    const [loading, setLoading] = useState(false);
    const [savingOrder, setSavingOrder] = useState(false);

    const [open, setOpen] = useState(false);
    const [editing, setEditing] = useState<Team | null>(null);
    const [form] = Form.useForm<TeamFormValues>();

    /** ✅ Firestore에서 teams 목록 실시간 구독 (order asc) */
    useEffect(() => {
        setLoading(true);

        const q = query(collection(db, TEAMS_COL), orderBy("order", "asc"));
        const unsub = onSnapshot(
            q,
            (snap) => {
                const data = snap.docs.map((d) => d.data() as Team);
                setTeams(data);
                teamsRef.current = data; // ✅ 최신값 보관
                setLoading(false);
            },
            (err) => {
                console.error(err);
                message.error("팀 목록을 불러오지 못했습니다.");
                setLoading(false);
            }
        );

        return () => unsub();
    }, []);

    /** ✅ order를 1..N으로 재부여해서 Firestore에 저장 (batch) */
    const persistOrderAsOneToN = async (orderedTeams: Team[]) => {
        const batch = writeBatch(db);
        orderedTeams.forEach((team, idx) => {
            const ref = doc(db, TEAMS_COL, String(team.id));
            batch.update(ref, { order: idx + 1 });
        });
        await batch.commit();
    };

    /** ✅ 컬럼 */
    const columns: ColumnsType<Team> = useMemo(
        () => [
            {
                title: "공격 순서 변경",
                dataIndex: "__drag",
                width: 120,
                align: "center",
                render: () => <DragHandle />,
            },
            { title: "공격 순서", dataIndex: "order", width: 120, render: (v?: number) => (v ? `${v}번` : "-"), },
            { title: "팀", dataIndex: "id", width: 150, render: (v?: number) => (v ? `${v}팀` : "-") },
            { title: "팀 이름", dataIndex: "name", width: 150 },
            {
                title: "비밀번호",
                dataIndex: "password",
                width: 150,
                render: (v: string) => v, // 필요하면 마스킹
            },
            {
                title: "",
                key: "actions",
                width: 180,
                align: "right",
                render: (_, record) => (
                    <Space>
                        <Button disabled={savingOrder} onClick={() => onEdit(record)}>
                            수정
                        </Button>
                        <Button disabled={savingOrder} danger onClick={() => onDelete(record)}>
                            삭제
                        </Button>
                    </Space>
                ),
            },
        ],
        [savingOrder]
    );

    /** 팀 추가 버튼 */
    const onCreate = () => {
        setEditing(null);
        form.resetFields();
        setOpen(true);
    };

    /** 팀 수정 버튼 */
    const onEdit = (team: Team) => {
        setEditing(team);
        form.setFieldsValue({
            id: team.id,
            name: team.name,
            password: team.password,
        });
        setOpen(true);
    };

    /** ✅ 팀 추가: (중복 체크) + order = max(order)+1 */
    const createTeam = async (values: TeamFormValues) => {
        const currentTeams = teamsRef.current; // ✅ 최신
        const teamId = values.id;
        const teamRef = doc(db, TEAMS_COL, String(teamId));

        const snap = await getDoc(teamRef);
        if (snap.exists()) throw new Error("DUPLICATE_ID");

        const maxOrder = currentTeams.reduce((max, t) => Math.max(max, t.order ?? 0), 0);
        const nextOrder = maxOrder + 1;

        const newTeam: Team = {
            id: teamId,
            name: values.name,
            password: values.password,
            order: nextOrder,
        };

        await setDoc(teamRef, newTeam);

        // ✅ “항상 연속(order=1..N)”을 강제하려면 아래를 켜도 됨(팀<=10이면 부담X)
        // const after = [...currentTeams, newTeam].sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
        // const normalized = after.map((t, idx) => ({ ...t, order: idx + 1 }));
        // await persistOrderAsOneToN(normalized);
    };

    /** ✅ 팀 수정: name/password만 업데이트 (id/order 유지) */
    const updateTeam = async (teamId: number, values: TeamFormValues) => {
        const ref = doc(db, TEAMS_COL, String(teamId));
        await updateDoc(ref, {
            name: values.name,
            password: values.password,
        });
    };

    /** ✅ 팀 삭제: "삭제 + order 1..N 재정렬"을 batch 1번으로 */
    const onDelete = (team: Team) => {
        Modal.confirm({
            title: "팀을 삭제할까요?",
            content: `${team.id}팀(${team.name})`,
            okText: "삭제",
            okType: "danger",
            cancelText: "취소",
            onOk: async () => {
                try {
                    setSavingOrder(true);

                    const currentTeams = teamsRef.current;
                    const remaining = currentTeams.filter((t) => t.id !== team.id);

                    // order 기준 정렬 후 1..N 재부여
                    const normalized = remaining
                        .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
                        .map((t, idx) => ({ ...t, order: idx + 1 }));

                    const batch = writeBatch(db);

                    // 1) 삭제
                    batch.delete(doc(db, TEAMS_COL, String(team.id)));

                    // 2) 남은 애들 order 업데이트
                    normalized.forEach((t) => {
                        batch.update(doc(db, TEAMS_COL, String(t.id)), { order: t.order });
                    });

                    await batch.commit();

                    message.success("삭제되었습니다.");
                    // ✅ setTeams를 여기서 굳이 건드리지 않음: onSnapshot이 바로 갱신해줌 (깜빡임 최소)
                } catch (e) {
                    console.error(e);
                    message.error("삭제 실패");
                } finally {
                    setSavingOrder(false);
                }
            },
        });
    };

    /** 모달 저장 */
    const onSave = async () => {
        try {
            const values = await form.validateFields();

            if (!editing) {
                try {
                    await createTeam(values);
                    message.success("추가되었습니다.");
                    setOpen(false);
                    form.resetFields();
                } catch (e: any) {
                    if (e?.message === "DUPLICATE_ID") {
                        message.error("이미 존재하는 ID입니다.");
                        return;
                    }
                    console.error(e);
                    message.error("추가 실패");
                }
            } else {
                try {
                    await updateTeam(editing.id, values);
                    message.success("수정되었습니다.");
                    setOpen(false);
                    setEditing(null);
                    form.resetFields();
                } catch (e) {
                    console.error(e);
                    message.error("수정 실패");
                }
            }
        } catch {
            // validation error
        }
    };

    /** DnD sensors */
    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } })
    );

    /** ✅ 드래그 종료: UI 순서 변경 + Firestore에 order=1..N 즉시 저장 */
    const onDragEnd = async (event: DragEndEvent) => {
        const { active, over } = event;
        if (!over) return;

        const activeId = String(active.id);
        const overId = String(over.id);
        if (activeId === overId) return;

        const currentTeams = teamsRef.current;

        const oldIndex = currentTeams.findIndex((t) => String(t.id) === activeId);
        const newIndex = currentTeams.findIndex((t) => String(t.id) === overId);
        if (oldIndex < 0 || newIndex < 0) return;

        // 1) UI 즉시 반영(낙관적)
        const moved = arrayMove(currentTeams, oldIndex, newIndex);
        const normalized = moved.map((t, idx) => ({ ...t, order: idx + 1 }));
        setTeams(normalized);
        teamsRef.current = normalized;

        // 2) Firestore 저장
        try {
            setSavingOrder(true);
            await persistOrderAsOneToN(normalized);
            message.success("순서가 저장되었습니다.");
        } catch (e) {
            console.error(e);
            message.error("순서 저장 실패");
        } finally {
            setSavingOrder(false);
        }
    };

    return (
        <Card
            title="팀 관리"
            extra={
                <Button type="primary" onClick={onCreate} disabled={savingOrder}>
                    팀 추가
                </Button>
            }
        >
            <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={onDragEnd}
            >
                <SortableContext
                    items={teams.map((t) => String(t.id))}
                    strategy={verticalListSortingStrategy}
                >
                    <Table<Team>
                        rowKey={(r) => String(r.id)}
                        columns={columns}
                        dataSource={teams}
                        loading={loading || savingOrder}
                        pagination={false}
                        components={{ body: { row: SortableRow } }}
                    />
                </SortableContext>
            </DndContext>

            <Modal
                open={open}
                title={editing ? "팀 수정" : "팀 추가"}
                onOk={onSave}
                onCancel={() => {
                    setOpen(false);
                    setEditing(null);
                    form.resetFields();
                }}
                okText="저장"
                cancelText="취소"
                destroyOnClose
            >
                <Form<TeamFormValues> form={form} layout="vertical">
                    <Form.Item
                        label="팀 (숫자)"
                        name="id"
                        rules={[{ required: true, message: "ID를 입력하세요." }]}
                    >
                        <InputNumber min={1} style={{ width: "100%" }} disabled={!!editing} />
                    </Form.Item>

                    <Form.Item
                        label="팀 이름"
                        name="name"
                        rules={[{ required: true, message: "팀 이름을 입력하세요." }]}
                    >
                        <Input />
                    </Form.Item>

                    <Form.Item
                        label="비밀번호"
                        name="password"
                        rules={[{ required: true, message: "비밀번호를 입력하세요." }]}
                    >
                        <Input.Password />
                    </Form.Item>

                    <Form.Item label="공격 순서">
                        <Input value={editing?.order ?? "자동(추가 시 마지막 순서)"} disabled />
                    </Form.Item>
                </Form>
            </Modal>
        </Card>
    );
}