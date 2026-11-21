import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import PatientDashboard from '../components/PatientDashboard'
import { NotificationProvider } from '../contexts/NotificationContext'
import axios from 'axios'

vi.mock('axios')

const renderDashboard = (props = {}) =>
  render(
    <NotificationProvider>
      <PatientDashboard user={{ name: 'Test Patient', email: 'patient@test.com' }} {...props} />
    </NotificationProvider>
  )

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  // Default: every GET resolves with "nothing yet" payloads so the component
  // falls back to its seed state without network errors.
  axios.get.mockImplementation((url) => {
    if (url.includes('getAllDoctors')) {
      return Promise.resolve({ data: { message: 'No doctors available' } })
    }
    if (url.includes('uploads')) {
      return Promise.resolve({ data: { message: 'No uploads yet' } })
    }
    if (url.includes('/appointments')) {
      return Promise.resolve({ data: { message: 'No appointments yet' } })
    }
    if (url.includes('reports') || url.includes('report')) {
      return Promise.resolve({ data: {} })
    }
    if (url.includes('getpatientallreport')) {
      return Promise.resolve({ data: {} })
    }
    if (url.includes('getpdetails')) {
      return Promise.resolve({ data: {} })
    }
    return Promise.resolve({ data: {} })
  })
})

describe('PatientDashboard — tab structure', () => {
  it('renders the four tabs: Processing, Doctors, Uploads, Completed', () => {
    renderDashboard()
    expect(screen.getByRole('button', { name: /processing/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /doctors/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /uploads/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /completed/i })).toBeInTheDocument()
  })

  it('shows the Processing tab by default with its empty state', () => {
    renderDashboard()
    expect(screen.getByText(/processing appointments/i)).toBeInTheDocument()
    expect(screen.getByText(/no processing appointments/i)).toBeInTheDocument()
  })
})

describe('PatientDashboard — Processing tab', () => {
  const processingApt = {
    _id: 'apt-1',
    doctor: 'Dr. Test',
    doctor_email: 'dr@test.com',
    date: '2026-01-10',
    time: '10:00 AM',
    status: 'Processing',
  }
  const confirmedApt = {
    _id: 'apt-2',
    doctor: 'Dr. Other',
    date: '2026-02-10',
    time: '11:00 AM',
    status: 'Confirmed',
  }

  beforeEach(() => {
    axios.get.mockImplementation((url) => {
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { message: 'No doctors available' } })
      }
      if (url.includes('uploads')) {
        return Promise.resolve({ data: { message: 'No uploads yet' } })
      }
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({
          data: { status: 200, appointments: [processingApt, confirmedApt] },
        })
      }
      return Promise.resolve({ data: {} })
    })
  })

  it('shows only Processing-status appointments as cards', async () => {
    renderDashboard()
    await waitFor(() => expect(screen.getByText('Dr. Test')).toBeInTheDocument())
    expect(screen.getByText(/processing appointments/i)).toBeInTheDocument()
    // The Confirmed appointment must NOT appear in the Processing list
    expect(screen.queryByText('Dr. Other')).not.toBeInTheDocument()
  })

  it('opens appointment details with images and clinical data when a card is clicked', async () => {
    const user = userEvent.setup()
    const pdetails = {
      patientEmail: 'patient@test.com',
      ultrasound_data: ['data:image/png;base64,AAA', 'data:image/png;base64,BBB'],
      clinical_data: { 'Serum creatinine (mg/dl)': 1.1, 'Blood urea (mg/dl)': 22 },
      description: 'Routine check',
    }
    axios.get.mockImplementation((url) => {
      if (url.includes('getpdetails')) return Promise.resolve({ data: { pdetails } })
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, appointments: [processingApt] } })
      }
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { message: 'No doctors available' } })
      }
      return Promise.resolve({ data: {} })
    })

    renderDashboard()
    const card = await screen.findByText('Dr. Test')
    await user.click(card)

    // Detail view replaces the card list
    expect(await screen.findByText(/back to list/i)).toBeInTheDocument()
    expect(screen.getByText(/appointment with dr\.test\.com/i)).toBeInTheDocument()

    // Images rendered
    const images = screen.getAllByAltText(/ultrasound image/i)
    expect(images).toHaveLength(2)

    // Clinical data table rendered
    expect(screen.getByText('Serum creatinine (mg/dl)')).toBeInTheDocument()
    expect(screen.getByText('Blood urea (mg/dl)')).toBeInTheDocument()

    // "See Result" button present
    expect(screen.getByRole('button', { name: /see result/i })).toBeInTheDocument()
  })

  it('calls "See Result" and fetches the report using the appointment _id', async () => {
    const user = userEvent.setup()
    const report = {
      predict: 'No_Disease',
      'Serum creatinine (mg/dl)': 1.0,
      'Blood urea (mg/dl)': 25,
      'Hemoglobin level (gms)': 14,
      'Blood pressure (mm/Hg)': 120,
      'Albumin in urine': 0,
    }
    axios.get.mockImplementation((url) => {
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, appointments: [processingApt] } })
      }
      if (url.includes('/reports/apt-1')) {
        return Promise.resolve({ data: { report } })
      }
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { message: 'No doctors available' } })
      }
      return Promise.resolve({ data: {} })
    })

    renderDashboard()
    const card = await screen.findByText('Dr. Test')
    await user.click(card)
    await user.click(await screen.findByRole('button', { name: /see result/i }))

    await waitFor(() => {
      expect(axios.get).toHaveBeenCalledWith('/reports/reports/apt-1', { withCredentials: true })
    })
    // The report viewer opens with the fetched prediction
    expect(await screen.findByText('CKD Report')).toBeInTheDocument()
    expect(screen.getByText('No_Disease')).toBeInTheDocument()
  })

  it('returns to the card list when "Back to list" is clicked', async () => {
    const user = userEvent.setup()
    renderDashboard()
    const card = await screen.findByText('Dr. Test')
    await user.click(card)
    await user.click(await screen.findByText(/back to list/i))
    // The card list is visible again
    expect(screen.getByText('Dr. Test')).toBeInTheDocument()
  })
})

describe('PatientDashboard — Doctors tab', () => {
  const doctors = [
    {
      _id: 'd1',
      email: 'dr.sarah@test.com',
      specialization: 'Nephrologist',
      experienceYears: 8,
      rating: 4,
      availability: ['2026-01-15', '2026-01-20'],
    },
  ]

  beforeEach(() => {
    axios.get.mockImplementation((url) => {
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { docdata: doctors } })
      }
      return Promise.resolve({ data: {} })
    })
  })

  it('shows all doctors with their available dates', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await user.click(screen.getByRole('button', { name: /doctors/i }))

    expect(await screen.findByText('dr.sarah@test.com')).toBeInTheDocument()
    expect(screen.getByText('Nephrologist')).toBeInTheDocument()
    expect(screen.getByText('8 years experience')).toBeInTheDocument()
    // Availability dates rendered as day/month buttons
    const list = screen.getByText('Available Dates').closest('.availability')
    expect(list).not.toBeNull()
    expect(within(list).getAllByRole('button')).toHaveLength(2)
  })
})

describe('PatientDashboard — Uploads tab', () => {
  it('shows all uploads of the user', async () => {
    const user = userEvent.setup()
    const uploads = [
      { id: 1, name: 'Blood Test Report.pdf', uploadDate: '2026-01-05', status: 'Under Review', doctor: 'Dr. Sarah Johnson' },
      { id: 2, name: 'Ultrasound.png', uploadDate: '2026-01-08', status: 'Reviewed', doctor: 'Dr. Raj' },
    ]
    axios.get.mockImplementation((url) => {
      if (url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, alluploads: uploads } })
      }
      return Promise.resolve({ data: {} })
    })

    renderDashboard()
    await user.click(screen.getByRole('button', { name: /uploads/i }))

    expect(await screen.findByText('Blood Test Report.pdf')).toBeInTheDocument()
    expect(screen.getByText('Ultrasound.png')).toBeInTheDocument()
    expect(screen.getByText('Dr. Sarah Johnson')).toBeInTheDocument()
    expect(screen.getByText('Under Review')).toBeInTheDocument()
  })

  it('shows the empty state when there are no uploads', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await user.click(screen.getByRole('button', { name: /uploads/i }))
    expect(await screen.findByText(/no uploads yet/i)).toBeInTheDocument()
  })
})

describe('PatientDashboard — Completed tab', () => {
  const completedApt = {
    _id: 'apt-c1',
    doctor: 'Dr. Done',
    doctor_email: 'done@test.com',
    date: '2025-12-01',
    time: '09:00 AM',
    status: 'Completed',
  }
  const processingApt = {
    _id: 'apt-p1',
    doctor: 'Dr. Working',
    date: '2026-01-10',
    time: '10:00 AM',
    status: 'Processing',
  }

  beforeEach(() => {
    axios.get.mockImplementation((url) => {
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, appointments: [completedApt, processingApt] } })
      }
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { message: 'No doctors available' } })
      }
      return Promise.resolve({ data: {} })
    })
  })

  it('shows only Completed-status appointments as cards', async () => {
    const user = userEvent.setup()
    renderDashboard()
    await user.click(screen.getByRole('button', { name: /completed/i }))

    expect(await screen.findByText(/completed appointments/i)).toBeInTheDocument()
    expect(screen.getByText('Dr. Done')).toBeInTheDocument()
    expect(screen.queryByText('Dr. Working')).not.toBeInTheDocument()
  })

  it('shows the empty state when there are no completed appointments', async () => {
    const user = userEvent.setup()
    axios.get.mockImplementation((url) => {
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { message: 'No appointments yet' } })
      }
      return Promise.resolve({ data: {} })
    })
    renderDashboard()
    await user.click(screen.getByRole('button', { name: /completed/i }))
    expect(await screen.findByText(/no completed appointments/i)).toBeInTheDocument()
  })

  it('fetches and displays the report when a completed appointment is clicked', async () => {
    const user = userEvent.setup()
    const report = {
      predict: 'Stage_2',
      'Serum creatinine (mg/dl)': 1.8,
      'Blood urea (mg/dl)': 45,
      'Hemoglobin level (gms)': 10,
      'Blood pressure (mm/Hg)': 145,
      'Albumin in urine': 2,
    }
    axios.get.mockImplementation((url) => {
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, appointments: [completedApt] } })
      }
      if (url.includes('/reports/apt-c1')) {
        return Promise.resolve({ data: { report } })
      }
      if (url.includes('getAllDoctors')) {
        return Promise.resolve({ data: { message: 'No doctors available' } })
      }
      return Promise.resolve({ data: {} })
    })

    renderDashboard()
    await user.click(screen.getByRole('button', { name: /completed/i }))
    const card = await screen.findByText('Dr. Done')
    await user.click(card)

    await waitFor(() => {
      expect(axios.get).toHaveBeenCalledWith('/reports/reports/apt-c1', { withCredentials: true })
    })
    expect(await screen.findByText('Stage_2')).toBeInTheDocument()
  })

  it('alerts the user when the report fetch fails', async () => {
    const user = userEvent.setup()
    axios.get.mockImplementation((url) => {
      if (url.includes('/appointments') && !url.includes('uploads')) {
        return Promise.resolve({ data: { status: 200, appointments: [completedApt] } })
      }
      if (url.includes('/reports/')) {
        return Promise.reject(new Error('network error'))
      }
      return Promise.resolve({ data: {} })
    })

    renderDashboard()
    await user.click(screen.getByRole('button', { name: /completed/i }))
    await user.click(await screen.findByText('Dr. Done'))

    await waitFor(() => {
      expect(window.alert).toHaveBeenCalledWith('Could not fetch the report for this appointment')
    })
  })
})

describe('PatientDashboard — sidebar navigation sync', () => {
  it('maps activeView "upload" to the uploads tab', () => {
    renderDashboard({ activeView: 'upload' })
    expect(screen.getByText(/your uploads/i)).toBeInTheDocument()
  })

  it('maps activeView "appointments" to the doctors tab', () => {
    renderDashboard({ activeView: 'appointments' })
    expect(screen.getByText(/find your doctor/i)).toBeInTheDocument()
  })

  it('maps activeView "reports" to the completed tab', () => {
    renderDashboard({ activeView: 'reports' })
    expect(screen.getByText(/completed appointments/i)).toBeInTheDocument()
  })
})
