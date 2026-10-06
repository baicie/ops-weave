import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

export const root = fileURLToPath(new URL('../../', import.meta.url))
export const output = join(root, 'dist', 'devctl')
export const platforms = ['windows', 'linux', 'darwin']
export const architectures = ['amd64', 'arm64']

export function hostTarget() {
  const platform = { win32: 'windows', linux: 'linux', darwin: 'darwin' }[process.platform]
  const arch = { x64: 'amd64', arm64: 'arm64' }[process.arch]
  if (!platform || !arch) throw new Error(`不支持当前平台 ${process.platform}/${process.arch}`)
  return { platform, arch }
}

export function artifact({ platform, arch }) {
  return join(output, `${platform}-${arch}`, `opsweave-dev${platform === 'windows' ? '.exe' : ''}`)
}
