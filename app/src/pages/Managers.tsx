import React, { useMemo, useState, useEffect, useRef } from "react";
import { Card, Table, Button, Space, Modal, Form, Input, InputNumber, message } from "antd";
import type { ColumnsType } from "antd/es/table";

import {
    collection,
    doc,
    onSnapshot,
    query,
    orderBy,
    deleteDoc,
    updateDoc,
    setDoc,
    getDoc,
} from "firebase/firestore";
import { db } from "../lib/firebase";
import type { ManagerAccount } from "../types/model";

type ManagerFormValues = {
    id: number;
    name: string;
    password: string;
};

const MANAGERS_COL = "managers";

export default function Managers() {
    const [managers, setManagers] = useState<ManagerAccount[]>([]);
    const managersRef = useRef<ManagerAccount[]>([]);
    const [loading, setLoading] = useState(false);

    const [open, setOpen] = useState(false);
    const [editing, setEditing] = useState<ManagerAccount | null>(null);
    const [form] = Form.useForm<ManagerFormValues>();

    /** ✅ Firestore에서 managers 목록 실시간 구독 (id asc) */
    useEffect(() => {
        setLoading(true);

        const q = query(collection(db, MANAGERS_COL), orderBy("id", "asc"));
        const unsub = onSnapshot(
            q,
            (snap) => {
                const data = snap.docs.map((d) => d.data() as ManagerAccount);
                setManagers(data);
                managersRef.current = data; // ✅ 최신값 보관
                setLoading(false);
            },
            (err) => {
                console.error(err);
                message.error("운영 계정 목록을 불러오지 못했습니다.");
                setLoading(false);
            }
        );

        return () => unsub();
    }, []);

    /** ✅ 컬럼(Teams.tsx와 동일한 톤) */
    const columns: ColumnsType<ManagerAccount> = useMemo(
        () => [
            { title: "팀", dataIndex: "id", width: 150, render: (v?: number) => (v ? `운영 ${v}팀` : "-") },
            { title: "이름", dataIndex: "name", width: 150 },
            {
                title: "비밀번호",
                dataIndex: "password",
                width: 150,
                // 필요 시 마스킹: render: () => "******"
                render: (v: string) => v,
            },
            {
                title: "",
                key: "actions",
                width: 180,
                align: "right",
                render: (_, record) => (
                    <Space>
                        <Button onClick={() => onEdit(record)}>수정</Button>
                        <Button danger onClick={() => onDelete(record)}>삭제</Button>
                    </Space>
                ),
            },
        ],
        []
    );

    /** 추가 버튼 */
    const onCreate = () => {
        setEditing(null);
        form.resetFields();
        setOpen(true);
    };

    /** 수정 버튼 */
    const onEdit = (account: ManagerAccount) => {
        setEditing(account);
        form.setFieldsValue({
            id: account.id,
            name: account.name,
            password: account.password,
        });
        setOpen(true);
    };

    /** ✅ 추가: ID 중복 체크 + setDoc */
    const createManager = async (values: ManagerFormValues) => {
        const current = managersRef.current;

        const id = values.id;
        if (!Number.isFinite(id) || id <= 0) throw new Error("INVALID_ID");

        // ✅ 문서 ID는 String(id)
        const ref = doc(db, MANAGERS_COL, String(id));

        // 1) id 중복 체크 (단독 사용이므로 getDoc으로 충분)
        const snap = await getDoc(ref);
        if (snap.exists()) throw new Error("DUPLICATE_ID");

        // (선택) 추가로 "필드 id" 중복도 방어하고 싶으면:
        // if (current.some((m) => m.id === id)) throw new Error("DUPLICATE_ID");

        const newAccount: ManagerAccount = {
            id,
            name: values.name,
            password: values.password,
        };

        await setDoc(ref, newAccount);
    };

    /** ✅ 수정: name/password만 업데이트 (id 변경 금지) */
    const updateManager = async (id: number, values: ManagerFormValues) => {
        const ref = doc(db, MANAGERS_COL, String(id));
        await updateDoc(ref, {
            name: values.name,
            password: values.password,
        });
    };

    /** 삭제 */
    const onDelete = (account: ManagerAccount) => {
        Modal.confirm({
            title: "운영 계정을 삭제할까요?",
            content: `ID=${account.id} (${account.name})`,
            okText: "삭제",
            okType: "danger",
            cancelText: "취소",
            onOk: async () => {
                try {
                    await deleteDoc(doc(db, MANAGERS_COL, String(account.id)));
                    message.success("삭제되었습니다.");
                } catch (e) {
                    console.error(e);
                    message.error("삭제 실패");
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
                    await createManager(values);
                    message.success("추가되었습니다.");
                    setOpen(false);
                    form.resetFields();
                } catch (e: any) {
                    if (e?.message === "INVALID_ID") {
                        message.error("ID는 1 이상의 숫자여야 합니다.");
                        return;
                    }
                    if (e?.message === "DUPLICATE_ID") {
                        message.error("이미 존재하는 ID입니다.");
                        return;
                    }
                    console.error(e);
                    message.error("추가 실패");
                }
            } else {
                try {
                    await updateManager(editing.id, values);
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

    return (
        <Card
            title="운영 계정 관리"
            extra={
                <Button type="primary" onClick={onCreate}>
                    운영 계정 추가
                </Button>
            }
        >
            <Table<ManagerAccount>
                rowKey={(r) => String(r.id)}
                columns={columns}
                dataSource={managers}
                loading={loading}
                pagination={false}
            />

            <Modal
                open={open}
                title={editing ? "운영 계정 수정" : "운영 계정 추가"}
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
                <Form<ManagerFormValues> form={form} layout="vertical">
                    <Form.Item
                        label="팀 (숫자)"
                        name="id"
                        rules={[
                            { required: true, message: "ID를 입력하세요." },
                            {
                                validator: (_, value) => {
                                    if (value == null) return Promise.resolve();
                                    if (Number.isFinite(value) && value > 0) return Promise.resolve();
                                    return Promise.reject(new Error("ID는 1 이상의 숫자여야 합니다."));
                                },
                            },
                        ]}
                    >
                        {/* 수정 시 ID 변경 막기 (Teams.tsx와 동일) */}
                        <InputNumber min={1} style={{ width: "100%" }} disabled={!!editing} />
                    </Form.Item>

                    <Form.Item
                        label="이름"
                        name="name"
                        rules={[{ required: true, message: "이름을 입력하세요." }]}
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
                </Form>
            </Modal>
        </Card>
    );
}
``