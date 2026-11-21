import assert from 'node:assert/strict';
import { jsPDF } from 'jspdf';

const document = new jsPDF('p', 'mm', 'a4');
document.text('Container build fixture', 10, 10);
const bytes = document.output('arraybuffer');
assert.ok(bytes.byteLength > 100);
assert.equal(new TextDecoder().decode(bytes.slice(0, 5)), '%PDF-');
console.log('PDF smoke test passed');
