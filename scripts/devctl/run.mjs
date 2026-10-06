#!/usr/bin/env node
import { existsSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { constants } from 'node:os'
import { artifact, hostTarget, root } from './artifacts.mjs'

try {
  const binary = artifact(hostTarget())
  if (!existsSync(binary)) {
    console.error('缺少当前平台的 opsweave-dev 产物。请先运行 pnpm dev:build（仅构建需要 Go 1.23+），或放入对应平台的已编译产物。')
    process.exitCode = 1
  } else {
    const child = spawn(binary, process.argv.slice(2), { cwd: root, stdio: 'inherit', shell: false })
    const forwarders = new Map(['SIGINT', 'SIGTERM'].map(signal => [signal, () => child.kill(signal)]))
    const cleanup = () => {
      for (const [signal, forward] of forwarders) process.off(signal, forward)
    }
    for (const [signal, forward] of forwarders) process.on(signal, forward)
    child.on('error', error => {
      cleanup()
      console.error(`无法运行 opsweave-dev（${error.code ?? 'UNKNOWN'}）。请检查产物及执行权限。`)
      process.exitCode = 1
    })
    child.on('exit', (code, signal) => {
      cleanup()
      process.exitCode = code ?? (128 + (constants.signals[signal] ?? 1))
    })
  }
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
