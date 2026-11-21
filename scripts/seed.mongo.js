// Privileged local demo provisioning, never an unauthenticated application endpoint.
const admin = db.getSiblingDB("admin");
admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD);
const data = JSON.parse(process.env.CKD_DEMO_DATA);
const target = db.getSiblingDB(data.database);
const patient = target.users.findOne({email: data.patient, role: "Patient"});
const doctor = target.users.findOne({email: data.doctor});
if (!patient || !doctor) throw new Error("Demo users must be registered first");
target.users.updateOne({_id: doctor._id}, {$set: {role: "Doctor", specialization: "Nephrology"}});
target.appointments.createIndex({seed_key: 1}, {unique: true, partialFilterExpression: {seed_key: {$exists: true}}});
target.appointments.updateOne({seed_key: "ckd-demo-appointment"}, {$setOnInsert: {
    seed_key: "ckd-demo-appointment",
    patient_email: data.patient,
    doctor_email: data.doctor,
    appointment_date: new Date(data.date + "T00:00:00Z"),
    status: "pending",
    ultrasound_data: data.image ? [data.image] : [],
    clinical_data: {age: 50, bp: 80, sg: 1.02, al: 0, su: 0, bgr: 110, bu: 30, sc: 1.0,
        sod: 140, pot: 4.2, hemo: 14, pcv: 42, wc: 8000, rc: 5.0,
        rbc: "normal", pc: "normal", pcc: "notpresent", ba: "notpresent", htn: "no", dm: "no",
        cad: "no", appet: "good", pe: "no", ane: "no"}
}}, {upsert: true});
