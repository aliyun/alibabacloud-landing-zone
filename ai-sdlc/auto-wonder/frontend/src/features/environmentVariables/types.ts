export interface EnvironmentVariable {
  id: number;
  name: string;
  value: '**';
  description: string | null;
  gmtCreate: string;
  gmtModified: string;
  version: number;
}

export interface CreateEnvironmentVariableInput {
  name: string;
  value: string;
  description?: string;
}

export interface UpdateEnvironmentVariableInput {
  name: string;
  description?: string;
  updateValue: boolean;
  value?: string;
}
