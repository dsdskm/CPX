package com.aba.cpx.data.model

/**
 * DB 저장 규칙:
 * - status는 DB에는 반드시 영어 문자열로 저장: "preparing" | "ready" | "working" | "completed"
 *
 * 앱 사용 규칙:
 * - 앱 내부에서는 TeamStatus(enum)로만 다룬다 (잘못된 값 컴파일 단계에서 차단)
 * - UI에는 status.labelKo 로 한글 표시
 */
data class Team(
    val id: Int,
    val name: String,
    val order: Int = 0,
    val password: String = "",
    val status: TeamStatus = TeamStatus.PREPARING
) {
    /** UI 표시용 한글 텍스트 */
    val statusTextKo: String get() = status.labelKo

    /** DB 저장용 영어 key */
    val statusKey: String get() = status.key

    companion object {
        /**
         * DB에서 읽어온 값(Map 형태 / Firestore document.data 등)으로 Team 생성
         * - status는 DB에 "preparing" 같은 문자열로 있다고 가정
         * - 누락/이상 값이면 PREPARING으로 안전 처리
         *
         * 필요에 따라 cast 부분은 프로젝트 상황에 맞게 조정하세요.
         */
        fun fromDbMap(map: Map<String, Any?>): Team {
            return Team(
                id = (map["id"] as? Number)?.toInt() ?: 0,
                name = map["name"] as? String ?: "",
                order = (map["order"] as? Number)?.toInt() ?: 0,
                password = map["password"] as? String ?: "",
                status = TeamStatus.fromKey(map["status"] as? String)
            )
        }
    }

    /**
     * DB에 저장할 Map 생성
     * - status는 영어 key로 저장됨
     */
    fun toDbMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "order" to order,
        "password" to password,
        "status" to status.key
    )
}

/**
 * 팀 상태는 아래 4개만 허용
 * DB 저장값(key)과 UI 표시값(labelKo)을 함께 관리
 */
enum class TeamStatus(val key: String, val labelKo: String) {
    PREPARING("preparing", "준비중"),
    READY("ready", "준비 완료"),
    WORKING("working", "진행중"),
    COMPLETED("completed", "종료");

    companion object {
        /**
         * DB/네트워크에서 들어온 문자열을 enum으로 안전 변환
         * - null/공백/알 수 없는 값이면 PREPARING 기본
         */
        fun fromKey(key: String?): TeamStatus {
            if (key.isNullOrBlank()) return PREPARING
            return entries.firstOrNull { it.key == key } ?: PREPARING
        }
    }
}