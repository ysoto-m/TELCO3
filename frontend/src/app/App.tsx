import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from '../pages/LoginPage'; import AgentPage from '../pages/AgentPage'; import AdminPage from '../pages/AdminPage';
const Guard=({children}:{children:any})=>localStorage.getItem('token')?children:<Navigate to='/login'/>;
export const App=()=> <Routes><Route path='/login' element={<LoginPage/>}/><Route path='/agent' element={<Guard><AgentPage/></Guard>}/><Route path='/admin' element={<Guard><AdminPage/></Guard>}/><Route path='*' element={<Navigate to='/login'/>}/></Routes>;
