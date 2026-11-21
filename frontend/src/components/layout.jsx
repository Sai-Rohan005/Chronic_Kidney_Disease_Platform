import React, { useEffect, useState } from 'react';
import Header from './Header';
import Sidebar from './Sidebar';
import axios from 'axios';
import DoctorDashboard from './DoctorDashboard';
import PatientDashboard from './PatientDashboard';
import { useNavigate } from 'react-router-dom';


const Authentication_url = "/auth";
const Layout = () => {
  const [isSidebarOpen, setSidebarOpen] = useState(true);
  const [dashboard, setDashboard] = useState('doctor-dashboard'); // role-level dashboard
  const [activeSection, setActiveSection] = useState('dashboard'); // tab/section inside dashboard
  const [userRole, setUserRole] = useState('Patient');
  const [isMobile,setIsMobile]=useState(window.innerWidth <= 450);
  const [changeoftab,setchangeoftab]=useState("dashboard");
  useEffect(()=>{
    setIsMobile(window.innerWidth <= 768);
  },[])
  const navigate=useNavigate();

  useEffect(() => {
    const fetchUserRole = async () => {
      try {
        const token = localStorage.getItem('token');
        const user_role = await axios.get(`${Authentication_url}/getRoleByToken`, {
          headers: {
            Authorization: `Bearer ${token}`
          }
        });
        if (user_role.data.role === 'Doctor') {
          setUserRole("Doctor");
          setDashboard("doctor-dashboard");
        } else if (user_role.data.role === "Patient") {
          setUserRole("Patient");
          setDashboard("patient-dashboard");
        } else {
          setUserRole("Admin");
          setDashboard("admin-dashboard");
        }
      } catch (e) {
        console.log(e);
        navigate('/login')
      }
    };
    fetchUserRole();
  }, []);
  useEffect(()=>{
    <Sidebar
          isOpen={isSidebarOpen}
          activeView={changeoftab}
          setActiveView={setActiveSection}
          userRole={userRole}
        />
  },[changeoftab])

  const toggleSidebar = () => {
    setSidebarOpen((prev) => !prev);
  };

  const renderContent = () => {
    if (dashboard === 'doctor-dashboard') {
      return <DoctorDashboard activeView={activeSection} />;
    }
    if (dashboard === 'patient-dashboard') {
      return <PatientDashboard activeView={activeSection} />;
    }
    return <div>Select a section</div>;
  };

  return isMobile ? (
    <>
      <main className="content">{renderContent()}</main>
    </>
  ) : (
    <div className={`layout ${(isSidebarOpen && !isMobile) ? 'sidebar-open' : 'sidebar-collapsed'}`}>
      <Header onToggleSidebar={toggleSidebar} />
      <div className="main-wrapper">
        <Sidebar
          isOpen={isSidebarOpen}
          activeView={activeSection}
          setActiveView={setActiveSection}
          userRole={userRole}
        />
        <main className="content">{renderContent()}</main>
      </div>
    </div>
  );
};

export default Layout;
