import React from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import PrivateRoute from "./auth/PrivateRoute";


import Login from "./pages/Login";
import AdminLayout from "./layouts/AdminLayout";
import Teams from "./pages/Teams";
import Managers from "./pages/Managers";
import BattleResults from "./pages/BattleResults";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />

        <Route
          path="/"
          element={
            <PrivateRoute>
              <AdminLayout />
            </PrivateRoute>
          }
        >
          <Route index element={<Navigate to="/teams" replace />} />
          <Route path="teams" element={<Teams />} />
          <Route path="managers" element={<Managers />} />
          <Route path="battle-results" element={<BattleResults />} />
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}