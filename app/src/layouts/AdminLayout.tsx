import React, { useMemo } from "react";
import { Layout, Menu, Button } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { TeamOutlined, UserOutlined, TrophyOutlined, LogoutOutlined, SettingOutlined } from "@ant-design/icons";
import { signOut } from "firebase/auth";
import { auth } from "../lib/firebase";

const { Sider, Header, Content } = Layout;

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  const selectedKey = useMemo(() => location.pathname, [location.pathname]);

  const items = [
    { key: "/config", icon: <SettingOutlined />, label: "설정" },
    { key: "/teams", icon: <TeamOutlined />, label: "팀 관리" },
    { key: "/managers", icon: <UserOutlined />, label: "운영 계정 관리" },
    { key: "/battle-results", icon: <TrophyOutlined />, label: "전투 결과" },
  ];

  const onLogout = async () => {
    await signOut(auth);
    navigate("/login");
  };

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Sider theme="dark" width={240}>
        <div style={{ color: "#fff", padding: 16, fontWeight: 700 }}>Admin</div>
        <Menu
          theme="dark"
          mode="inline"
          items={items}
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: "#fff",
            display: "flex",
            alignItems: "center",
            justifyContent: "flex-end",
            padding: "0 16px",
          }}
        >
          <Button icon={<LogoutOutlined />} onClick={onLogout}>
            로그아웃
          </Button>
        </Header>

        <Content style={{ padding: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
