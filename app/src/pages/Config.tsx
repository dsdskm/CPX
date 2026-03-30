import React, { useState, useEffect } from "react";
import { Card, Form, Input, Button, Upload, message, Spin, Image, Space } from "antd";
import { UploadOutlined, AlertFilled, DeleteOutlined } from "@ant-design/icons";
import type { RcFile, UploadFile } from "antd/es/upload/interface";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { db } from "../lib/firebase";
import { uploadImage } from "../lib/api";
import type { Config } from "../types/model";

const CONFIG_PATH = "config";
const CONFIG_DOC = "main";

export default function ConfigPage() {
    const [form] = Form.useForm<Config>();
    const [loading, setLoading] = useState(true);
    const [uploading, setUploading] = useState(false);
    const [imageUrl, setImageUrl] = useState<string>("");
    const [fileList, setFileList] = useState<UploadFile[]>([]);

    // 초기 Config 데이터 로드
    useEffect(() => {
        loadConfig();
    }, []);

    const loadConfig = async () => {
        try {
            setLoading(true);
            const docRef = doc(db, CONFIG_PATH, CONFIG_DOC);
            const docSnap = await getDoc(docRef);

            if (docSnap.exists()) {
                const data = docSnap.data() as Config;
                form.setFieldsValue({
                    text: data.text,
                    url: data.url,
                });
                if (data.url) {
                    setImageUrl(data.url);
                }
            }
        } catch (err) {
            console.error("Config 로드 실패:", err);
            message.error("설정을 불러오지 못했습니다.");
        } finally {
            setLoading(false);
        }
    };

    const beforeUpload = (file: RcFile) => {
        const isImage = file.type.startsWith("image/");
        if (!isImage) {
            message.error("이미지 파일만 업로드 가능합니다.");
            return false;
        }

        const isLess5M = file.size / 1024 / 1024 < 5;
        if (!isLess5M) {
            message.error("이미지 크기는 5MB 이하여야 합니다.");
            return false;
        }

        return true;
    };

    const handleUploadChange = ({ fileList: newFileList }: { fileList: UploadFile[] }) => {
        setFileList(newFileList);
    };

    const onDeleteImage = async () => {
        try {
            setUploading(true);
            setImageUrl("");
            const docRef = doc(db, CONFIG_PATH, CONFIG_DOC);
            await setDoc(docRef, {
                text: form.getFieldValue("text") || "",
                url: "",
            });
            message.success("이미지가 삭제되었습니다.");
        } catch (err) {
            console.error("이미지 삭제 실패:", err);
            message.error("이미지 삭제에 실패했습니다.");
        } finally {
            setUploading(false);
        }
    };

    const onSubmit = async (values: Config) => {
        try {
            setUploading(true);

            let imageUrl = values.url || "";

            // 새로운 이미지가 선택된 경우
            if (fileList.length > 0 && fileList[0].originFileObj) {
                const file = fileList[0].originFileObj as RcFile;
                imageUrl = await uploadImage(CONFIG_PATH, file);
                setImageUrl(imageUrl);
                message.success("이미지가 업로드되었습니다.");
            }

            // Firestore에 저장
            const docRef = doc(db, CONFIG_PATH, CONFIG_DOC);
            await setDoc(docRef, {
                text: values.text || "",
                url: imageUrl,
            });

            message.success("설정이 저장되었습니다.");
            setFileList([]);
        } catch (err) {
            console.error("저장 실패:", err);
            message.error("설정 저장에 실패했습니다.");
        } finally {
            setUploading(false);
        }
    };

    if (loading) {
        return <Spin />;
    }

    return (
        <div style={{ maxWidth: 800 }}>
            <Card title="설정" loading={uploading}>
                <Form
                    form={form}
                    layout="vertical"
                    onFinish={onSubmit}
                    autoComplete="off"
                >
                    {/* 텍스트 필드 */}
                    <Form.Item
                        label="텍스트"
                        name="text"
                    >
                        <Input.TextArea rows={4} placeholder="설정 텍스트를 입력하세요." />
                    </Form.Item>

                    {/* 이미지 업로드 */}
                    <Form.Item label="이미지 (16:9 비율로 올려주세요)">
                        <Upload
                            fileList={fileList}
                            onChange={handleUploadChange}
                            beforeUpload={beforeUpload}
                            accept="image/*"
                            maxCount={1}
                        >
                            <Button icon={<UploadOutlined />}>
                                이미지 선택
                            </Button>
                        </Upload>
                        <div style={{ marginTop: 8, fontSize: 12, color: "#999" }}>
                            
                            16:9 비율로 업로드해 주세요.
                        </div>
                    </Form.Item>

                    {/* 현재 이미지 미리보기 */}
                    {imageUrl && (
                        <Form.Item label="현재 이미지">
                            <Space direction="vertical" style={{ width: "100%" }}>
                                <Image src={imageUrl} />
                                <Button 
                                    danger 
                                    icon={<DeleteOutlined />} 
                                    onClick={onDeleteImage}
                                    loading={uploading}
                                >
                                    이미지 삭제
                                </Button>
                            </Space>
                        </Form.Item>
                    )}

                    {/* 제출 버튼 */}
                    <Form.Item>
                        <Button type="primary" htmlType="submit" loading={uploading}>
                            저장
                        </Button>
                    </Form.Item>
                </Form>
            </Card>
        </div>
    );
}
