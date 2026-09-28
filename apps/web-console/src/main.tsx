import { createRoot } from 'react-dom/client'
import { App } from './app/App.tsx'
import './styles/globals.css'
import './styles/app.css'
import './styles/workspace.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing root element')
createRoot(root).render(<App />)
