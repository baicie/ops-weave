import { render } from '@zeus-js/zeus'
import { App } from './app/App.tsx'
import './adapters/zeus-ui/register.ts'
import './styles/app.css'

const root = document.getElementById('root')
if (!root) throw new Error('Missing root element')
render(() => <App />, root)
