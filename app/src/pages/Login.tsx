import React, { useState } from "react";
import { Card, Form, Input, Button, Typography, message } from "antd";
import {
    signInWithEmailAndPassword,
    sendPasswordResetEmail,
} from "firebase/auth";
import { auth } from "../lib/firebase";
import { useNavigate } from "react-router-dom";

type LoginForm = {
    email: string;
    password: string;
};

export default function Login() {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [form] = Form.useForm<LoginForm>();

    /** 로그인 */
    const onFinish = async (values: LoginForm) => {
        try {
            setLoading(true);
            await signInWithEmailAndPassword(auth, values.email, values.password);
            navigate("/", { replace: true });
        } catch {
            message.error("로그인 실패: 이메일 또는 비밀번호를 확인하세요.");
        } finally {
            setLoading(false);
        }
    };

    /** 비밀번호 재설정 메일 발송 */
    const onResetPassword = async () => {
        const email = form.getFieldValue("email");

        if (!email) {
            message.warning("비밀번호 재설정을 위해 이메일을 먼저 입력하세요.");
            return;
        }

        try {
            await sendPasswordResetEmail(auth, email);
            message.success("비밀번호 재설정 메일을 발송했습니다.");
        } catch {
            message.error("비밀번호 재설정 메일 발송에 실패했습니다.");
        }
    };

    return (
        <div style={{ height: "100vh", display: "grid", placeItems: "center" }}>
            <Card style={{ width: 380 }}>
                <Typography.Title level={3} style={{ marginBottom: 16 }}>
                    관리자 로그인
                </Typography.Title>

                <Form<LoginForm>
                    form={form}
                    layout="vertical"
                    onFinish={onFinish}
                >
                    <Form.Item
                        label="이메일"
                        name="email"
                        rules={[
                            { required: true, message: "이메일을 입력하세요." },
                            { type: "email", message: "이메일 형식이 올바르지 않습니다." },
                        ]}
                    >
                        <Input placeholder="admin@company.com" />
                    </Form.Item>

                    <Form.Item
                        label="비밀번호"
                        name="password"
                        rules={[{ required: true, message: "비밀번호를 입력하세요." }]}
                    >
                        <Input.Password />
                    </Form.Item>

                    <Button
                        type="primary"
                        htmlType="submit"
                        block
                        loading={loading}
                    >
                        로그인
                    </Button>

                    <div style={{ marginTop: 8, textAlign: "right" }}>
                        <Button
                            type="link"
                            onClick={onResetPassword}
                            style={{ padding: 0 }}
                        >
                            비밀번호를 잊으셨나요?
                        </Button>
                    </div>
                </Form>
            </Card>
        </div>
    );
}