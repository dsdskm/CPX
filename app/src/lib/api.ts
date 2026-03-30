import { ref, uploadBytes, getDownloadURL, deleteObject } from "firebase/storage";
import { storage } from "./firebase";

/**
 * Firebase Storage에 파일을 업로드하고 다운로드 URL을 반환합니다.
 * @param path - 저장할 경로 (예: "config/image.jpg")
 * @param file - 업로드할 파일
 * @returns 업로드된 파일의 다운로드 URL
 */
export const uploadFile = async (path: string, file: File): Promise<string> => {
    try {
        const storageRef = ref(storage, path);
        const snapshot = await uploadBytes(storageRef, file);
        const url = await getDownloadURL(snapshot.ref);
        return url;
    } catch (error) {
        console.error("파일 업로드 실패:", error);
        throw error;
    }
};

/**
 * 고유한 타임스탐프를 포함한 파일 경로를 생성합니다.
 * @param folder - 폴더 (예: "config")
 * @param file - 파일 객체
 * @returns 생성된 파일 경로
 */
export const generateConfigPath = (folder: string, file: File): string => {
    const timestamp = new Date().getTime();
    return `${folder}/${timestamp}_${file.name}`;
};

/**
 * Firebase Storage에 이미지 파일을 업로드합니다.
 * @param folder - 저장할 폴더 (예: "config")
 * @param file - 업로드할 이미지 파일
 * @returns 업로드된 이미지의 다운로드 URL
 */
export const uploadImage = async (folder: string, file: File): Promise<string> => {
    const path = generateConfigPath(folder, file);
    return uploadFile(path, file);
};

/**
 * Firebase Storage에서 파일을 삭제합니다.
 * @param path - 삭제할 파일의 경로 (예: "config/image.jpg")
 */
export const deleteFile = async (path: string): Promise<void> => {
    try {
        const storageRef = ref(storage, path);
        await deleteObject(storageRef);
    } catch (error) {
        console.error("파일 삭제 실패:", error);
        throw error;
    }
};

/**
 * Firebase Storage의 파일 다운로드 URL을 가져옵니다.
 * @param path - 파일의 경로 (예: "config/image.jpg")
 * @returns 파일의 다운로드 URL
 */
export const getFileDownloadUrl = async (path: string): Promise<string> => {
    try {
        const storageRef = ref(storage, path);
        const url = await getDownloadURL(storageRef);
        return url;
    } catch (error) {
        console.error("파일 URL 조회 실패:", error);
        throw error;
    }
};

/**
 * URL에서 Firebase Storage 경로를 추출합니다.
 * @param url - Firebase Storage 다운로드 URL
 * @returns 저장된 경로
 */
export const extractPathFromUrl = (url: string): string => {
    try {
        // URL에서 경로 부분 추출 (조정 필요 시 수정)
        const decodedUrl = decodeURIComponent(url);
        const pathMatch = decodedUrl.match(/\/o\/(.+?)\?/);
        return pathMatch ? pathMatch[1] : url;
    } catch (error) {
        console.error("경로 추출 실패:", error);
        return url;
    }
};
