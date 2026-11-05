import React from 'react';
import {createRoot} from 'react-dom/client';
import {ReactFlowProvider} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import './style.css';
import App from './App.jsx';
createRoot(document.getElementById('root')).render(<ReactFlowProvider><App/></ReactFlowProvider>);
