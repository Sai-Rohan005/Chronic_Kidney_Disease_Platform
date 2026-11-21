import React, { useState, useEffect, useRef, useMemo } from 'react'
import './PatientDashboard.css'
import { useNotifications } from '../contexts/NotificationContext'
import axios from 'axios'
import jsPDF from 'jspdf'
import html2canvas from 'html2canvas'
import { FaDownload } from 'react-icons/fa'

// ---------------------------------------------------------------------------
// Proxy base URLs (set in the config file)
// ---------------------------------------------------------------------------
const Authentication_url = '/auth'
const Appointment_url = '/appointments'
const Report_url = '/reports'
const Prediction_url = '/predictions'

// ---------------------------------------------------------------------------
// Endpoints: change the paths here only. Everything is built from the proxy
// variables above, so nothing else in the file needs to be touched.
// ---------------------------------------------------------------------------
const ENDPOINTS = {
  // GET -> { appointments: [{ _id, date, time, status, doctor_email, ... }] }
  listAppointments: () => `${Appointment_url}`,

  // GET -> { appointment: { ultrasound_data: [imgUrl, ...], clinical_data: { ... } } }
  appointmentDetails: () => `${Appointment_url}/getAppointment`,

  // GET -> { docdata: [{ _id, email, specialization, experienceYears, rating, availability: [date, ...] }] }
  listDoctors: () => `${Authentication_url}/getAllDoctors`,

  // POST { doctor, date, time, problem } -> { message: 'Appointment booked successfully' }
  bookAppointment: () => `${Appointment_url}/bookappointment`,

  // GET -> { report: { predict, ...clinical values } }   (result shown after "See results")
  appointmentResult: () => `${Report_url}/getReport`,
  // Prediction_url is available here too if the result should come from predictions:
  // appointmentResult: (id) => `${Prediction_url}/predictions/${id}`,
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
const getId = (apt) => apt?._id || apt?.id
const sameStatus = (status, wanted) => String(status || '').toLowerCase() === wanted.toLowerCase()
const fmtDate = (d) => (d ? new Date(d).toLocaleDateString() : '—')
const imgSrcOf = (img) => (typeof img === 'string' ? img : img?.url || img?.src || '')

const TAB_FROM_VIEW = {
  appointments: 'doctors',
  doctors: 'doctors',
  reports: 'completed',
  analytics: 'completed',
  completed: 'completed',
  processing: 'processing',
}

// Reference ranges used on the result report
const RANGES = [
  { key: 'Serum creatinine (mg/dl)', label: 'Serum creatinine (mg/dl)', min: 0.6, max: 1.3, text: '0.6 – 1.3 mg/dl' },
  { key: 'Blood urea (mg/dl)', label: 'Blood urea (mg/dl)', min: 10, max: 40, text: '10 – 40 mg/dl' },
  { key: 'Hemoglobin level (gms)', label: 'Hemoglobin level (gms)', min: 12, max: 16, text: '12 – 16 g/dl (typical adult)' },
  { key: 'Blood pressure (mm/Hg)', label: 'Blood pressure (mm/Hg)', min: 90, max: 130, text: '90 – 130 mm/Hg' },
  { key: 'Albumin in urine', label: 'Albumin in urine', min: 0, max: 0, text: '0 (negative)' },
]

const STAGE_TABLE = [
  ['Serum creatinine (mg/dl)', '0.6 – 1.3', '1.3 – 1.5', '1.5 – 2.0', '2.0 – 3.5', '3.5 – 5.0', '5.0 – 12.0'],
  ['Blood urea (mg/dl)', '10 – 40', '30 – 60', '50 – 80', '80 – 120', '120 – 150', '150 – 300'],
  ['Hemoglobin level (gms)', '12 – 16', '12 – 14', '11 – 13', '9 – 11', '8 – 10', '6 – 9'],
  ['Blood pressure (mm/Hg)', '90 – 130', '130 – 140', '135 – 150', '145 – 160', '155 – 170', '170 – 200'],
  ['Albumin in urine', '0', '1 – 2', '2 – 3', '3 – 4', '4 – 5', '5'],
]

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------
const PatientDashboard = ({ user, activeView, onBackToHome, defaultActiveTab }) => {
  const { notifyAppointmentBooked } = useNotifications()

  const [activeTab, setActiveTab] = useState(TAB_FROM_VIEW[defaultActiveTab] || 'processing')

  // Appointments
  const [appointments, setAppointments] = useState([])
  const [appointmentsLoading, setAppointmentsLoading] = useState(true)

  // Selected appointment + its images / clinical data
  const [selectedAppointment, setSelectedAppointment] = useState(null)
  const [details, setDetails] = useState(null)
  const [detailsLoading, setDetailsLoading] = useState(false)
  const [detailsError, setDetailsError] = useState('')
  const [previewImage, setPreviewImage] = useState(null)

  // Result ("See results")
  const [result, setResult] = useState(null)
  const [resultLoading, setResultLoading] = useState(false)
  const reportRef = useRef(null)

  // Doctors
  const [doctors, setDoctors] = useState([])
  const [doctorsLoading, setDoctorsLoading] = useState(true)
  const [problems, setProblems] = useState({}) // { [doctorId]: 'text' }
  const [bookingKey, setBookingKey] = useState(null)

  // -------------------------------------------------------------------------
  // Sidebar navigation -> tab
  // -------------------------------------------------------------------------
  useEffect(() => {
    if (!activeView) return
    setActiveTab(TAB_FROM_VIEW[activeView] || 'processing')
  }, [activeView])

  // Leave any open appointment / result when switching tabs
  const changeTab = (tab) => {
    setActiveTab(tab)
    closeAppointment()
  }

  // -------------------------------------------------------------------------
  // Data loading
  // -------------------------------------------------------------------------
  useEffect(() => {
    const load = async () => {
      try {
        const token = localStorage.getItem('token');
        const res = await axios.get(ENDPOINTS.listAppointments(), {
          withCredentials: true,
          headers: {
            Authorization: `Bearer ${token}`
          }
        })
        setAppointments(Array.isArray(res.data?.appointments) ? res.data.appointments : [])
      } catch (err) {
        console.log(err)
      } finally {
        setAppointmentsLoading(false)
      }
    }
    load()
  }, [])

  useEffect(() => {
    const load = async () => {
      try {
        const token = localStorage.getItem('token');
        const res = await axios.get(ENDPOINTS.listDoctors(), {
          withCredentials: true,
          headers: {
            Authorization: `Bearer ${token}`
          }
        })
        setDoctors(Array.isArray(res.data?.docdata) ? res.data.docdata : [])
      } catch (err) {
        console.log(err)
      } finally {
        setDoctorsLoading(false)
      }
    }
    load()
  }, [])

  const processing = useMemo(() => appointments.filter((a) => sameStatus(a.status, 'Processing')), [appointments])
  const completed = useMemo(() => appointments.filter((a) => sameStatus(a.status, 'Completed')), [appointments])

  // -------------------------------------------------------------------------
  // Appointment open / close
  // -------------------------------------------------------------------------
  const openAppointment = async (apt) => {
    const id = getId(apt)
    setSelectedAppointment(apt)
    setDetails(null)
    setDetailsError('')
    setResult(null)
    setPreviewImage(null)
    if (!id) {
      setDetailsError('This appointment has no id, so its data cannot be loaded.')
      return
    }
    try {
      setDetailsLoading(true)
      const token = localStorage.getItem('token')
      const res = await axios.get(ENDPOINTS.appointmentDetails(),{appointmentId: id}, { withCredentials: true, headers: { Authorization: `Bearer ${token}` } })
      const data = res.data?.appointment || res.data?.pdetails || res.data || {}
      setDetails({
        images: data.ultrasound_data || data.images || [],
        clinical: data.clinical_data || {},
        description: data.description || data.problem || '',
      })
    } catch (err) {
      console.log(err)
      setDetailsError('Could not load the data for this appointment. Try again.')
    } finally {
      setDetailsLoading(false)
    }
  }

  const closeAppointment = () => {
    setSelectedAppointment(null)
    setDetails(null)
    setDetailsError('')
    setResult(null)
    setPreviewImage(null)
  }

  // -------------------------------------------------------------------------
  // See results
  // -------------------------------------------------------------------------
  const fetchResult = async () => {
    const id = getId(selectedAppointment)
    if (!id) return
    try {
      setResultLoading(true)
      const token = localStorage.getItem('token')
      const res = await axios.get(ENDPOINTS.appointmentResult(), { appointmentId: id }, { withCredentials: true, headers: { Authorization: `Bearer ${token}` } })
      const payload = res.data?.report || res.data
      if (!payload || (typeof payload === 'object' && Object.keys(payload).length === 0)) {
        alert('No result is available for this appointment yet.')
        return
      }
      setResult(payload)
    } catch (err) {
      console.log(err)
      alert('Could not fetch the result for this appointment.')
    } finally {
      setResultLoading(false)
    }
  }

  const downloadPdf = async () => {
    const element = reportRef.current
    if (!element) return
    const canvas = await html2canvas(element, { scale: 2 })
    const imgData = canvas.toDataURL('image/png')
    const pdf = new jsPDF('p', 'mm', 'a4')
    const pageW = pdf.internal.pageSize.getWidth()
    const pageH = pdf.internal.pageSize.getHeight()
    const imgH = (canvas.height * pageW) / canvas.width

    // Slice tall reports across several pages
    let heightLeft = imgH
    let position = 0
    pdf.addImage(imgData, 'PNG', 0, position, pageW, imgH)
    heightLeft -= pageH
    while (heightLeft > 0) {
      position = heightLeft - imgH
      pdf.addPage()
      pdf.addImage(imgData, 'PNG', 0, position, pageW, imgH)
      heightLeft -= pageH
    }
    pdf.save(`CKD_Report_${getId(selectedAppointment) || 'appointment'}.pdf`)
  }

  // -------------------------------------------------------------------------
  // Booking
  // -------------------------------------------------------------------------
  const handleBookAppointment = async (doctor, date, time) => {
    const problem = problems[doctor._id] || ''
    const key = `${doctor._id}-${date}`
    try {
      setBookingKey(key)
      const token = localStorage.getItem('token')
      const res = await axios.post(
        ENDPOINTS.bookAppointment(),
        { doctor: doctor.email, date, time, problem },
        { withCredentials: true, headers: { Authorization: `Bearer ${token}` } }
      )
      if (res.data?.message === 'Appointment booked successfully') {
        // Prefer the real appointment from the server so it has its _id
        const created = res.data.appointment
        setAppointments((prev) => [
          ...prev,
          created || {
            _id: res.data.appointmentId || `local-${Date.now()}`,
            doctor_email: doctor.email,
            date,
            time,
            status: 'Pending Confirmation',
          },
        ])
        setProblems((prev) => ({ ...prev, [doctor._id]: '' }))
        notifyAppointmentBooked(
          user?.name || 'Patient',
          user?.email,
          doctor.email,
          `${date} at ${time}`
        )
        alert('Appointment booked successfully')
      } else {
        alert(res.data?.message || 'Could not book the appointment')
      }
    } catch (err) {
      console.log(err)
      alert('Could not book the appointment. Try again.')
    } finally {
      setBookingKey(null)
    }
  }

  // -------------------------------------------------------------------------
  // Shared pieces
  // -------------------------------------------------------------------------
  const AppointmentCard = ({ apt, icon, badgeClass }) => (
    <div
      className="appointment-card card card-hover"
      onClick={() => openAppointment(apt)}
      style={{ cursor: 'pointer' }}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          openAppointment(apt)
        }
      }}
    >
      <div className="appointment-icon">{icon}</div>
      <div className="appointment-info">
        <p className="text-caption">Appointment ID</p>
        <h5 className="text-body" style={{ wordBreak: 'break-all' }}>{getId(apt)}</h5>
        <p className="text-caption">Date: {fmtDate(apt.date)}{apt.time ? ` at ${apt.time}` : ''}</p>
        <p className="text-caption">Doctor: {apt.doctor_email || apt.doctor || '—'}</p>
        <span className={`badge ${badgeClass}`}>{apt.status}</span>
      </div>
    </div>
  )

  const clockIcon = (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <polyline points="12,6 12,12 16,14"></polyline>
    </svg>
  )
  const checkIcon = (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="20,6 9,17 4,12"></polyline>
    </svg>
  )

  // Images + clinical data of the selected appointment
  const renderAppointmentData = () => {
    if (detailsLoading) return <p className="text-body">Loading appointment data...</p>
    if (detailsError) return <p className="text-body" style={{ color: '#b91c1c' }}>{detailsError}</p>
    if (!details) return null

    const images = (details.images || []).map(imgSrcOf).filter(Boolean)
    const clinicalEntries = Object.entries(details.clinical || {})

    return (
      <>
        <div className="images-section" style={{ marginTop: 16 }}>
          <h5 className="text-label">Images</h5>
          {images.length > 0 ? (
            <>
              <div className="images-grid" style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {images.map((src, i) => (
                  <img
                    key={i}
                    src={src}
                    alt={`Ultrasound ${i + 1}`}
                    onClick={() => setPreviewImage(src)}
                    style={{
                      height: 120,
                      borderRadius: 8,
                      objectFit: 'cover',
                      cursor: 'zoom-in',
                      outline: previewImage === src ? '2px solid #2563eb' : 'none',
                    }}
                  />
                ))}
              </div>
              {previewImage && (
                <div style={{ marginTop: 12 }}>
                  <img
                    src={previewImage}
                    alt="Selected ultrasound"
                    style={{ maxWidth: '100%', maxHeight: 480, borderRadius: 8 }}
                  />
                </div>
              )}
            </>
          ) : (
            <p className="text-caption">No images were uploaded for this appointment.</p>
          )}
        </div>

        <div className="clinical-data-section" style={{ marginTop: 16 }}>
          <h5 className="text-label">Clinical data</h5>
          {clinicalEntries.length > 0 ? (
            <table className="table">
              <thead>
                <tr>
                  <th>Metric</th>
                  <th>Value</th>
                </tr>
              </thead>
              <tbody>
                {clinicalEntries.map(([key, value]) => (
                  <tr key={key}>
                    <td>{key}</td>
                    <td>{String(value)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-caption">No clinical data was entered for this appointment.</p>
          )}
        </div>

        {details.description && (
          <p className="text-body" style={{ marginTop: 12 }}>Problem: {details.description}</p>
        )}
      </>
    )
  }

  // The result report shown after "See results"
  const renderResult = () => {
    // Result values win; fall back to the appointment's clinical data
    const values = { ...(details?.clinical || {}), ...(result || {}) }
    const inRange = (r) => {
      const v = Number(values[r.key])
      if (values[r.key] === undefined || values[r.key] === '' || Number.isNaN(v)) return null
      return v >= r.min && v <= r.max
    }

    return (
      <>
        <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10, marginBottom: 12 }}>
          <button
            onClick={downloadPdf}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              backgroundColor: '#007bff',
              color: 'white',
              border: 'none',
              borderRadius: 8,
              padding: '10px 16px',
              cursor: 'pointer',
            }}
          >
            <FaDownload size={18} />
            Download PDF
          </button>
          <button
            aria-label="Close result"
            onClick={() => setResult(null)}
            style={{
              backgroundColor: 'transparent',
              border: '1px solid #ccc',
              borderRadius: '50%',
              width: 32,
              height: 32,
              fontSize: 20,
              fontWeight: 'bold',
              color: '#555',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            ×
          </button>
        </div>

        <div ref={reportRef} className="ckd-report">
          <div className="wrap">
            <div className="card" style={{ position: 'relative' }}>
              <h1 className="title">Chronic Kidney Disease (CKD) Report</h1>
              <p className="subtitle">Confidential medical document • Appointment {getId(selectedAppointment)}</p>

              <div className="grid mb">
                <div className="section">
                  <h3>Appointment</h3>
                  <div className="row">
                    <div className="label">Patient</div>
                    <div className="value">{user?.name || 'Patient'}</div>
                  </div>
                  <div className="row">
                    <div className="label">Doctor</div>
                    <div className="value">{selectedAppointment?.doctor_email || selectedAppointment?.doctor || '—'}</div>
                  </div>
                  <div className="row">
                    <div className="label">Date</div>
                    <div className="value">{fmtDate(selectedAppointment?.date)}</div>
                  </div>
                  <div className="row">
                    <div className="label">Appointment ID</div>
                    <div className="value">{getId(selectedAppointment)}</div>
                  </div>
                </div>

                <div className="section">
                  <h3>Summary</h3>
                  <div className="row">
                    <div className="label">CKD Stage</div>
                    <div className="value"><span>{result?.predict ?? '—'}</span></div>
                  </div>
                  {RANGES.map((r) => (
                    <div className="row" key={r.key}>
                      <div className="label">{r.label}</div>
                      <div className="value"><span>{values[r.key] ?? '—'}</span></div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="section mb">
                <h3>Laboratory results</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Test</th>
                      <th>Result</th>
                      <th>Reference range</th>
                      <th>Flag</th>
                    </tr>
                  </thead>
                  <tbody>
                    {RANGES.map((r) => {
                      const ok = inRange(r)
                      return (
                        <tr key={r.key}>
                          <td>{r.label}</td>
                          <td>{values[r.key] ?? '—'}</td>
                          <td>{r.text}</td>
                          <td>
                            {ok === null ? (
                              '—'
                            ) : (
                              <span className={ok ? 'badge ok' : 'badge warn'}>{ok ? 'Normal' : 'Abnormal'}</span>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              <div className="section mb">
                <h3>Stage-specific reference ranges</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Metric</th>
                      <th>No_Disease</th>
                      <th>Stage_1</th>
                      <th>Stage_2</th>
                      <th>Stage_3</th>
                      <th>Stage_4</th>
                      <th>Stage_5</th>
                    </tr>
                  </thead>
                  <tbody>
                    {STAGE_TABLE.map((row) => (
                      <tr key={row[0]}>
                        {row.map((cell, i) => (
                          <td key={i}>{cell}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="section">
                <h3>Doctor&apos;s notes &amp; recommendations</h3>
                {result?.notes ? (
                  <p className="text-body">{result.notes}</p>
                ) : (
                  <ul>
                    <li>Monitor renal function and blood pressure; lifestyle optimization.</li>
                    <li>Repeat labs as clinically indicated.</li>
                  </ul>
                )}
              </div>
            </div>
          </div>
        </div>
      </>
    )
  }

  // -------------------------------------------------------------------------
  // Tab 1: Processing appointments
  // -------------------------------------------------------------------------
  const renderProcessingTab = () => (
    <div className="appointments-section">
      <div className="section-header">
        <h3 className="text-heading-2">Processing appointments</h3>
        <p className="text-body-large">Select an appointment to see the images and clinical data you submitted</p>
      </div>

      {selectedAppointment ? (
        <div className="appointment-detail card">
          <button className="btn btn-outline-secondary" onClick={closeAppointment} style={{ marginBottom: 12 }}>
            ← Back to appointments
          </button>
          <h4 className="text-heading-3">Appointment {getId(selectedAppointment)}</h4>
          <p className="text-body">Doctor: {selectedAppointment.doctor_email || selectedAppointment.doctor || '—'}</p>
          <p className="text-body">
            Date: {fmtDate(selectedAppointment.date)}{selectedAppointment.time ? ` at ${selectedAppointment.time}` : ''}
          </p>
          {renderAppointmentData()}
        </div>
      ) : appointmentsLoading ? (
        <p className="text-body">Loading appointments...</p>
      ) : processing.length === 0 ? (
        <div className="no-documents">
          <h4>No processing appointments</h4>
          <p>Appointments with the status Processing will appear here</p>
        </div>
      ) : (
        <div className="appointments-list">
          {processing.map((apt) => (
            <AppointmentCard key={getId(apt)} apt={apt} icon={clockIcon} badgeClass="badge-warning text-dark" />
          ))}
        </div>
      )}
    </div>
  )

  // -------------------------------------------------------------------------
  // Tab 2: Doctors with available dates
  // -------------------------------------------------------------------------
  const renderDoctorsTab = () => (
    <div className="appointments-section">
      <div className="section-header">
        <h3 className="text-heading-2">Find your doctor</h3>
        <p className="text-body-large">Pick an available date to book a consultation</p>
      </div>

      {doctorsLoading ? (
        <p className="text-body">Loading doctors...</p>
      ) : doctors.length === 0 ? (
        <div className="no-documents">
          <h4>No doctors available</h4>
          <p>Check back later for available doctors</p>
        </div>
      ) : (
        <div className="doctors-grid">
          {doctors.map((doctor) => {
            const dates = doctor.availability || []
            return (
              <div key={doctor._id} className="doctor-card card card-hover">
                <div className="doctor-avatar">
                  <div className="avatar-placeholder">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                      <circle cx="12" cy="7" r="4"></circle>
                    </svg>
                  </div>
                </div>

                <div className="doctor-info">
                  <h4 className="text-heading-3">{doctor.email}</h4>
                  <p className="specialty text-body">{doctor.specialization}</p>
                  {doctor.experienceYears != null && (
                    <p className="experience text-caption">{doctor.experienceYears} years experience</p>
                  )}
                  {doctor.rating != null && (
                    <div className="rating">
                      <div className="stars">
                        {[...Array(5)].map((_, i) => (
                          <svg
                            key={i}
                            width="16"
                            height="16"
                            viewBox="0 0 24 24"
                            fill={i < Math.floor(doctor.rating) ? 'currentColor' : 'none'}
                            stroke="currentColor"
                            strokeWidth="2"
                          >
                            <polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"></polygon>
                          </svg>
                        ))}
                        <span className="rating-text text-caption">{doctor.rating}</span>
                      </div>
                    </div>
                  )}
                </div>

                <input
                  type="text"
                  placeholder="Describe your problem"
                  className="form-control"
                  value={problems[doctor._id] || ''}
                  onChange={(e) => setProblems((prev) => ({ ...prev, [doctor._id]: e.target.value }))}
                />

                <div className="availability">
                  <h5 className="text-label">Available dates</h5>
                  {dates.length === 0 ? (
                    <p className="text-caption">No dates available right now</p>
                  ) : (
                    <div className="date-buttons">
                      {dates.map((date) => (
                        <button
                          key={date}
                          className="date-btn card-hover"
                          disabled={bookingKey === `${doctor._id}-${date}`}
                          onClick={() => {
                            const confirmed = window.confirm(
                              `Book an appointment with ${doctor.email} on ${new Date(date).toDateString()} at 10:00 AM?`
                            )
                            if (confirmed) handleBookAppointment(doctor, date, '10:00 AM')
                          }}
                        >
                          <span className="date-day text-body">{new Date(date).getDate()}</span>
                          <span className="date-month text-caption">
                            {new Date(date).toLocaleDateString('en-US', { month: 'short' })}
                          </span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )

  // -------------------------------------------------------------------------
  // Tab 3: Completed appointments
  // -------------------------------------------------------------------------
  const renderCompletedTab = () => (
    <div className="appointments-section">
      <div className="section-header">
        <h3 className="text-heading-2">Completed appointments</h3>
        <p className="text-body-large">Select an appointment to review its data and see the result</p>
      </div>

      {selectedAppointment ? (
        <div className="appointment-detail card">
          {result ? (
            <>
              <button
                className="btn btn-outline-secondary"
                onClick={() => setResult(null)}
                style={{ marginBottom: 12 }}
              >
                ← Back to appointment data
              </button>
              {renderResult()}
            </>
          ) : (
            <>
              <button className="btn btn-outline-secondary" onClick={closeAppointment} style={{ marginBottom: 12 }}>
                ← Back to appointments
              </button>

              {/* Green "See results" button, top middle */}
              <div style={{ display: 'flex', justifyContent: 'center', margin: '4px 0 16px' }}>
                <button
                  onClick={fetchResult}
                  disabled={resultLoading}
                  style={{
                    backgroundColor: '#16a34a',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 8,
                    padding: '10px 28px',
                    fontWeight: 600,
                    cursor: resultLoading ? 'not-allowed' : 'pointer',
                    opacity: resultLoading ? 0.7 : 1,
                  }}
                >
                  {resultLoading ? 'Loading result...' : 'See results'}
                </button>
              </div>

              <h4 className="text-heading-3">Appointment {getId(selectedAppointment)}</h4>
              <p className="text-body">Doctor: {selectedAppointment.doctor_email || selectedAppointment.doctor || '—'}</p>
              <p className="text-body">
                Date: {fmtDate(selectedAppointment.date)}{selectedAppointment.time ? ` at ${selectedAppointment.time}` : ''}
              </p>
              {renderAppointmentData()}
            </>
          )}
        </div>
      ) : appointmentsLoading ? (
        <p className="text-body">Loading appointments...</p>
      ) : completed.length === 0 ? (
        <div className="no-documents">
          <h4>No completed appointments</h4>
          <p>Completed appointments and their results will appear here</p>
        </div>
      ) : (
        <div className="appointments-list">
          {completed.map((apt) => (
            <AppointmentCard key={getId(apt)} apt={apt} icon={checkIcon} badgeClass="badge-success text-white" />
          ))}
        </div>
      )}
    </div>
  )

  // -------------------------------------------------------------------------
  // Layout
  // -------------------------------------------------------------------------
  return (
    <div className="patient-dashboard">
      <div className="dashboard-header">
        <h2 className="text-heading-1">Welcome, {user?.name || 'Patient'}</h2>
        <p className="text-body-large">Track your appointments, find doctors, and view your results.</p>
      </div>

      <div className="dashboard-tabs">
        <button className={activeTab === 'processing' ? 'active' : ''} onClick={() => changeTab('processing')}>
          {clockIcon}
          Processing
        </button>
        <button className={activeTab === 'doctors' ? 'active' : ''} onClick={() => changeTab('doctors')}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
            <circle cx="12" cy="7" r="4"></circle>
          </svg>
          Doctors
        </button>
        <button className={activeTab === 'completed' ? 'active' : ''} onClick={() => changeTab('completed')}>
          {checkIcon}
          Medical Reports
        </button>
      </div>

      <div className="dashboard-content">
        {activeTab === 'processing' && renderProcessingTab()}
        {activeTab === 'doctors' && renderDoctorsTab()}
        {activeTab === 'completed' && renderCompletedTab()}
      </div>
    </div>
  )
}

export default PatientDashboard