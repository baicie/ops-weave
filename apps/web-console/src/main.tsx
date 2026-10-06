import { createRoot } from 'react-dom/client'
import { App } from './app/App.tsx'
import './styles/globals.css'
import './styles/app.css'
import './styles/workspace.css'
import './styles/admin.css'
import './styles/workflow-studio.css'
import './styles/workflow-comparison.css'
import './styles/integration-center.css'
import './styles/source-setup.css'
import './styles/source-metric-discovery.css'
import './styles/console.css'
import './styles/navigation.css'
import './styles/workspace-layout.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing root element')
createRoot(root).render(<App />)
