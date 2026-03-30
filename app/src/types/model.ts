export interface Team {
  id: number;
  name: string;
  password: string;
  order: number;
  status: string;
}

export interface ManagerAccount {
  id: number;
  name: string;
  password: string;
}

export interface Config {
  text: string;
  url: string;
}
