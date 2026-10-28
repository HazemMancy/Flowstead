// The Flowstead mark as on the brand sheet: two teal streams meet at a white node, which sits in a gap ring and
// leaves as one outlet. Geometry measured from the sheet (units = sheet pixels); outlet teal on the icon, blue in the lockup.
function flowsteadMark(outlet) {
  const id = 'm' + Math.random().toString(36).slice(2, 8);
  return `<defs>
    <linearGradient id="${id}t" x1="0" y1="11" x2="0" y2="124" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#17D7D2"/><stop offset="1" stop-color="#0BBDB8"/></linearGradient>
    <linearGradient id="${id}o" x1="110" y1="0" x2="158" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="${outlet[0]}"/><stop offset="1" stop-color="${outlet[1]}"/></linearGradient>
    <mask id="${id}k" maskUnits="userSpaceOnUse" x="0" y="0" width="200" height="140"><rect width="200" height="140" fill="#fff"/><circle cx="102.5" cy="66.5" r="20.5" fill="#000"/></mask>
  </defs>
  <g mask="url(#${id}k)">
    <path d="M18 17.5H64Q70 17.5 73.3 22.5L102.5 66.5M18 115.5H64Q70 115.5 73.3 110.5L102.5 66.5" fill="none" stroke="url(#${id}t)" stroke-width="12.5" stroke-linejoin="round"/>
    <rect x="13" y="11.25" width="12" height="12.5" rx="3" fill="#17D7D2"/>
    <rect x="13" y="109.25" width="12" height="12.5" rx="3" fill="#0BBDB8"/>
    <rect x="100" y="60.25" width="58" height="12.5" rx="3" fill="url(#${id}o)"/>
  </g>
  <circle cx="102.5" cy="66.5" r="13.5" fill="#FFFFFF"/>`;
}
const TEAL_OUTLET = ['#1ED9D6', '#24DBDC'];
const BLUE_OUTLET = ['#28B4E9', '#3AA8EE'];
