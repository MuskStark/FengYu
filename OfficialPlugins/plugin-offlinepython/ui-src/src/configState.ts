export interface WorkerConfig {
  python?: { version?: string; platforms?: string[] }
  download?: { onlyBinary?: boolean; recursive?: boolean }
}

export interface ConfigForm {
  pythonVersion: string
  platformsCsv: string
  onlyBinary: boolean
  recursive: boolean
}

export function configForm(config?: WorkerConfig): ConfigForm {
  return {
    pythonVersion: config?.python?.version ?? '3.12.10',
    platformsCsv: config?.python?.platforms?.join(', ') || 'win_amd64',
    onlyBinary: config?.download?.onlyBinary ?? true,
    recursive: config?.download?.recursive ?? true,
  }
}
