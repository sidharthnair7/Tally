import { useMemo, useRef, useState } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';
import GradientASCII from './components/GradientASCII.jsx';
import AnimatedContent from './components/AnimatedContent.jsx';
import GradientText from './components/GradientText.jsx';
import ScrollReveal from './components/ScrollReveal.jsx';
import SpotlightCard from './components/SpotlightCard.jsx';
import CountUp from './components/CountUp.jsx';
import { asciiPalette, useHue } from './rainbow.js';
import { isLite, useLite } from './quality.js';

gsap.registerPlugin(ScrollTrigger, useGSAP);

const GITHUB = 'https://github.com/sidharthnair7/Tally';

// Colors that follow the page hue. GradientText and SpotlightCard take them as plain CSS values.
const RAINBOW = ['var(--a1)', 'var(--a2)', 'var(--a3)', 'var(--a4)', 'var(--a5)'];

const CHAPTERS = [
  { id: 'prologue', label: 'Prologue' },
  { id: 'scatter', label: 'The scatter' },
  { id: 'checks', label: 'Every row' },
  { id: 'people', label: 'The people' },
  { id: 'receipts', label: 'Receipts' },
  { id: 'memory', label: 'Memory' },
  { id: 'epilogue', label: 'Epilogue' }
];

const SYSTEMS = [
  { name: 'ERP', tables: 'invoices · payments · policy', rows: '1,579' },
  { name: 'Vendor master', tables: 'vendors', rows: '45' },
  { name: 'HR', tables: 'employees', rows: '180' },
  { name: 'Bank feed', tables: 'transactions', rows: '905' }
];

const CHECKS = [
  {
    code: 'D1',
    name: 'Duplicate payment',
    systems: ['ERP', 'Vendor master'],
    rule: 'Same vendor, same amount, paid again inside the policy’s duplicate window.',
    found: '$18,450.00'
  },
  {
    code: 'D2',
    name: 'Vendor matches an employee',
    systems: ['Vendor master', 'HR', 'ERP'],
    rule: 'A vendor’s bank account or address is an employee’s. Only a disclosed, approved conflict clears it.',
    found: '$47,800.00'
  },
  {
    code: 'D3',
    name: 'Split to dodge approval',
    systems: ['ERP', 'Vendor master', 'HR'],
    rule: 'Same vendor, requester and day. Each invoice under the approval limit, together over it.',
    found: '$29,350.00'
  },
  {
    code: 'D4',
    name: 'Payment not in the books',
    systems: ['Bank feed', 'ERP', 'Vendor master'],
    rule: 'Money left the bank with no ERP payment behind it.',
    found: '$12,600.00'
  },
  {
    code: 'D5',
    name: 'New vendor, large payment',
    systems: ['Vendor master', 'ERP', 'HR'],
    rule: 'A payment at the CFO limit or above, to a vendor younger than the new-vendor window.',
    found: '$38,000.00'
  },
  {
    code: 'D6',
    name: 'Approved own invoice',
    systems: ['ERP', 'Vendor master', 'HR'],
    rule: 'The person who requested the invoice also approved it.',
    found: '$6,240.00'
  }
];

const ROWS = {
  'vendors.vendors:V-2044': [
    ['vendor_id', 'V-2044'],
    ['name', 'Apex Facility Services LLC'],
    ['bank_account', '••••0565'],
    ['created_date', '2026-05-14'],
    ['created_by', 'E-1007']
  ],
  'hr.employees:E-1007': [
    ['employee_id', 'E-1007'],
    ['name', 'Derek Holloway'],
    ['title', 'Accounts Payable Specialist'],
    ['bank_account', '••••0565']
  ],
  'erp.payments:PAY-300638': [
    ['payment_id', 'PAY-300638'],
    ['vendor_id', 'V-2044'],
    ['amount', '9,650.00'],
    ['paid_date', '2026-09-25'],
    ['method', 'ACH']
  ]
};

const TRUST = [
  ['Rules decide', 'Every verdict comes from a plain rule over the rows. A model never decides what is fraud.'],
  ['Checked words', 'Nemotron words the explanation. A sentence that fails the check is replaced by the rule’s own.'],
  ['Masked', 'Bank account numbers leave the server as their last four digits.'],
  ['Traceable', 'Every finding set aside points to the person who cleared it, and why.']
];

const FACTS = [
  { to: 6, suffix: ' / 6', label: 'planted frauds found' },
  { to: 9, down: true, label: 'other false alarms' },
  { to: 2709, separator: ',', label: 'records, every one checked' },
  { to: 32, label: 'federated SQL statements' }
];

// The canvas sizes itself once, so a change of quality remounts it (keyed on lite) to pick up the new pixel ratio.
// Above 1.5x the glyphs look no sharper, so that is the cap even on full quality.
function RainbowASCII({ offset = 0, ...props }) {
  const lite = useLite();
  const hue = useHue(lite ? 4 : 2);
  const colors = useMemo(() => asciiPalette(hue + offset), [hue, offset]);
  const dpr = Math.min(window.devicePixelRatio || 1, lite ? 1 : 1.5);
  return <GradientASCII key={lite ? 'lite' : 'full'} colors={colors} dpr={dpr} lightMode {...props} />;
}

// GradientText repaints its text on every frame it draws. A slow drift needs far fewer.
function RainbowText(props) {
  const lite = useLite();
  return <GradientText colors={RAINBOW} maxFps={lite ? 24 : 40} {...props} />;
}

function Cite({ id }) {
  const [system, rest] = id.split('.');
  const [table, key] = rest.split(':');
  return (
    <span className="cite" tabIndex={0}>
      <span className="cite-chip">
        {system}.{table} · <b>{key}</b>
      </span>
      <span className="cite-row" role="tooltip">
        <span className="cite-row-head">{id}</span>
        {ROWS[id].map(([k, v]) => (
          <span className="cite-field" key={k}>
            <i>{k}</i>
            <b>{v}</b>
          </span>
        ))}
      </span>
    </span>
  );
}

export default function App() {
  const root = useRef(null);
  const [active, setActive] = useState(0);
  // Blur in a reveal is a filter on every frame of the tween; lite mode keeps the motion and drops the blur.
  // Read once: changing it later would rebuild reveals that have already played and play them again.
  const [liteAtLoad] = useState(isLite);
  const soft = px => (liteAtLoad ? 0 : px);

  useGSAP(
    () => {
      const mm = gsap.matchMedia();

      mm.add(
        {
          motion: '(prefers-reduced-motion: no-preference)',
          wide: '(min-width: 861px)'
        },
        context => {
          const { motion, wide } = context.conditions;

          // Chapter I: four systems drift apart, then lock onto one SQL door.
          const scatter = gsap.timeline({
            defaults: { ease: 'none' },
            scrollTrigger: wide
              ? { trigger: '.scatter-stage', start: 'top top', end: '+=170%', pin: true, scrub: 1 }
              : { trigger: '.scatter-stage', start: 'top 75%', end: 'bottom 55%', scrub: 1 }
          });
          const spread = wide ? 1 : 0.45;
          const homes = [
            { x: -260, y: -150, rotation: -14 },
            { x: 250, y: -170, rotation: 11 },
            { x: -230, y: 170, rotation: 9 },
            { x: 270, y: 150, rotation: -12 }
          ];
          gsap.utils.toArray('.sys').forEach((card, i) => {
            scatter.from(
              card,
              {
                x: homes[i].x * spread,
                y: homes[i].y * spread,
                rotation: homes[i].rotation,
                scale: 0.86,
                opacity: 0.55,
                filter: isLite() ? 'none' : 'blur(3px)',
                duration: 1
              },
              0
            );
          });
          scatter
            .from('.hub', { scale: 0.2, autoAlpha: 0, duration: 0.45 }, 0.55)
            .fromTo(
              '.wire',
              { attr: { x2: 50, y2: 50 }, opacity: 0 },
              {
                attr: { x2: (_, el) => el.dataset.x, y2: (_, el) => el.dataset.y },
                opacity: 1,
                duration: 0.4,
                stagger: 0.05
              },
              0.7
            )
            .to('.scatter-copy .state-a', { autoAlpha: 0, y: -20, duration: 0.25 }, 0.5)
            .fromTo('.scatter-copy .state-b', { autoAlpha: 0, y: 20 }, { autoAlpha: 1, y: 0, duration: 0.3 }, 0.7)
            .to({}, { duration: 0.25 });

          // Chapter III: the lead draws itself, person first, then what they touched.
          const lead = gsap.timeline({
            defaults: { ease: 'none' },
            scrollTrigger: { trigger: '.graph', start: 'top 75%', end: 'bottom 55%', scrub: 0.8 }
          });
          lead
            .from('.node.person', { scale: 0.6, autoAlpha: 0, duration: 0.3 })
            .fromTo('.edge', { strokeDashoffset: 1 }, { strokeDashoffset: 0, duration: 0.5, stagger: 0.12 })
            .from('.node.vendor', { scale: 0.7, autoAlpha: 0, duration: 0.3, stagger: 0.12 }, '-=0.35')
            .from('.edge-label', { autoAlpha: 0, y: 8, duration: 0.25, stagger: 0.1 })
            .from('.escalate', { autoAlpha: 0, scale: 0.9, duration: 0.25 });

          if (!motion) return;

          // Prologue
          const intro = gsap.timeline({ defaults: { ease: 'expo.out' } });
          intro
            .from('.nav', { y: -24, autoAlpha: 0, duration: 1.1 })
            .from('.hero .eyebrow', { y: 14, autoAlpha: 0, duration: 1 }, '-=0.7')
            .from('.hero h1 .line > span', { yPercent: 115, duration: 1.4, stagger: 0.14 }, '-=0.8')
            .from('.hero .ctas > *', { y: 16, autoAlpha: 0, duration: 0.9, stagger: 0.08 }, '-=0.7')
            .from('.scroll-cue', { autoAlpha: 0, duration: 1 }, '-=0.3');

          gsap.to('.hero-inner', {
            yPercent: -18,
            autoAlpha: 0,
            ease: 'none',
            scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true }
          });
          gsap.to('.hero-ascii', {
            scale: 1.08,
            autoAlpha: 0.25,
            ease: 'none',
            scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true }
          });

          // Chapter IV: the model writes, the verifier checks, one sentence at a time.
          const verify = gsap.timeline({ scrollTrigger: { trigger: '.verifier', start: 'top 70%' } });
          gsap.utils.toArray('.claim').forEach(claim => {
            verify
              .from(claim, { autoAlpha: 0, x: -14, duration: 0.5, ease: 'power3.out' })
              .from(claim.querySelector('.verdict'), { autoAlpha: 0, scale: 0.6, duration: 0.4, ease: 'back.out(2)' }, '+=0.25');
            if (claim.classList.contains('rejected')) {
              verify
                .to(claim.querySelector('.claim-text'), { opacity: 0.45, duration: 0.3 }, '+=0.1')
                .from(claim.querySelector('.strike'), { scaleX: 0, duration: 0.5, ease: 'power2.inOut' }, '<')
                .from(claim.querySelector('.replacement'), { autoAlpha: 0, height: 0, duration: 0.6, ease: 'power3.out' });
            }
          });

          // Chapter V: the decision is stamped, then the second run sets it aside.
          gsap
            .timeline({ scrollTrigger: { trigger: '.runs', start: 'top 65%' } })
            .from('.run.first', { autoAlpha: 0, y: 30, duration: 0.8, ease: 'power3.out' })
            .from('.decision', { autoAlpha: 0, scale: 0.85, rotate: -4, duration: 0.6, ease: 'back.out(1.8)' })
            .from('.run.second', { autoAlpha: 0, y: 30, duration: 0.8, ease: 'power3.out' }, '-=0.1')
            .from('.run.second .set-aside', { backgroundColor: 'rgba(80, 200, 140, 0.32)', duration: 1.2 }, '-=0.2');

          gsap.to('.scroll-cue i', { y: 8, duration: 1.1, ease: 'sine.inOut', yoyo: true, repeat: -1 });
        }
      );

      // The chapter rail follows whichever chapter holds the middle of the screen.
      CHAPTERS.forEach((chapter, index) => {
        ScrollTrigger.create({
          trigger: `#${chapter.id}`,
          start: 'top 50%',
          end: 'bottom 50%',
          onToggle: self => self.isActive && setActive(index)
        });
      });

      // The child components made their triggers before the pin above existed. Put every trigger back in page
      // order so the ones below the pin account for the space it adds.
      ScrollTrigger.sort();
      ScrollTrigger.refresh();

      return () => mm.revert();
    },
    { scope: root }
  );

  return (
    <div ref={root} className="page">
      <div className="aura" aria-hidden="true" />

      <nav className="nav">
        <a href="/" className="brand">
          Tally<span className="dot" />
        </a>
        <div className="nav-links">
          <a href="#scatter">The story</a>
          <a href="#checks">Checks</a>
          <a href="#receipts">Receipts</a>
          <a href={GITHUB}>GitHub</a>
        </div>
        <a href="/workspace" className="btn btn-glow btn-sm">
          Open the workspace
        </a>
      </nav>

      <ol className="rail" aria-label="Chapters">
        {CHAPTERS.map((chapter, index) => (
          <li key={chapter.id} className={index === active ? 'on' : index < active ? 'past' : ''}>
            <a href={`#${chapter.id}`}>
              <span className="rail-dot" />
              <span className="rail-label">{chapter.label}</span>
            </a>
          </li>
        ))}
      </ol>

      {/* ───────── Prologue ───────── */}
      <header className="hero" id="prologue">
        <div className="hero-ascii" aria-hidden="true">
          <RainbowASCII
            shape="horizon"
            characters=" .:-=+xX0$#@"
            cellSize={18}
            density={0.92}
            glow={0.2}
            glyphOpacity={1}
            flicker={0.25}
            depthOfField={0.06}
            grain={0.04}
            gradientOpacity={0.5}
            clickEffect="wave"
          />
        </div>
        <div className="hero-scrim" aria-hidden="true" />

        <div className="hero-inner">
          <p className="eyebrow">
            <span className="pulse" /> Brightwater Components · Q3 2026 · 2,709 records
          </p>
          <h1>
            <span className="line">
              <span>Somewhere in this quarter,</span>
            </span>
            <span className="line">
              <span>
                money left that{' '}
                <RainbowText variant="flow" animationSpeed={10} glow={0.3} className="hero-grad">
                  shouldn&rsquo;t have.
                </RainbowText>
              </span>
            </span>
          </h1>
          <p className="lede">
            Tally finds it, shows you the rows that prove it, and remembers what you cleared last time.
          </p>
          <div className="ctas">
            <a href="/workspace" className="btn btn-glow">
              Run an audit
            </a>
            <a href="#scatter" className="btn btn-ghost">
              Read the story
            </a>
          </div>
        </div>

        <a href="#scatter" className="scroll-cue">
          Scroll to begin <i>↓</i>
        </a>
      </header>

      {/* ───────── Chapter I · The scatter ───────── */}
      <section className="chapter" id="scatter">
        <div className="chapter-intro">
          <p className="kicker">
            <span>I</span> The scatter
          </p>
          <ScrollReveal
            baseOpacity={0.08}
            enableBlur={!liteAtLoad}
            blurStrength={6}
            baseRotation={0}
            highlightColor="var(--a2)"
            textClassName="story-text"
          >
            A controller signs off on thousands of payments a quarter. The proof of a duplicate payment, a fake vendor
            or a split invoice is never in one place. It hides between four systems that never talk.
          </ScrollReveal>
        </div>

        <div className="scatter-stage">
          <div className="scatter-copy">
            <div className="state state-a">
              <h2>
                Auditors sample. Most tools read <em>one system at a time.</em>
              </h2>
            </div>
            <div className="state state-b">
              <h2>
                Tally reads all four <em>where they live.</em>
              </h2>
              <p>One SQL door through Zetaris. Each system stays its own datasource, queried in place. Nothing is copied out first.</p>
            </div>
          </div>

          <div className="orbit">
            <svg className="wires" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              {[
                [24, 22],
                [76, 22],
                [24, 78],
                [76, 78]
              ].map(([x, y], i) => (
                <line key={i} className="wire" x1="50" y1="50" x2={x} y2={y} data-x={x} data-y={y} />
              ))}
            </svg>
            {SYSTEMS.map((system, i) => (
              <div className={`sys sys-${i}`} key={system.name}>
                <b>{system.name}</b>
                <span>{system.tables}</span>
                <small>{system.rows} rows</small>
              </div>
            ))}
            <div className="hub">
              <span className="hub-ring" />
              <b>Zetaris</b>
              <small>one SQL door</small>
            </div>
          </div>
        </div>
      </section>

      {/* ───────── Chapter II · Every row ───────── */}
      <section className="chapter" id="checks">
        <AnimatedContent distance={60} blur={soft(10)} duration={1.1} className="chapter-head">
          <p className="kicker">
            <span>II</span> Every row
          </p>
          <h2>
            Six checks. <em>Every transaction,</em> not a sample.
          </h2>
          <p className="sub">
            Each check is SQL that joins the systems, plus a plain rule that decides. The limits come from your own
            approval policy, read from the ERP.
          </p>
        </AnimatedContent>

        <AnimatedContent className="checks" stagger={0.09} distance={70} tilt={14} blur={soft(6)} duration={1} staggerFrom="start">
          {CHECKS.map((check, i) => (
            <SpotlightCard
              key={check.code}
              className="check"
              theme="light"
              spotlightColor={`oklch(0.72 0.17 calc(var(--h) + ${i * 52}))`}
              intensity={0.7}
              spotlightSize={300}
              borderGlow={0.9}
              proximity={120}
              grain={liteAtLoad ? 0 : 0.1}
              style={{ '--i': i }}
            >
              <div className="check-top">
                <span className="code">{check.code}</span>
                <span className="found">{check.found}</span>
              </div>
              <h3>{check.name}</h3>
              <p>{check.rule}</p>
              <div className="chips">
                {check.systems.map(s => (
                  <span key={s}>{s}</span>
                ))}
              </div>
            </SpotlightCard>
          ))}
        </AnimatedContent>

        <AnimatedContent className="total" distance={30} blur={soft(6)}>
          <span>At risk across the six planted frauds</span>
          <RainbowText animationSpeed={7} className="total-num">
            <CountUp to={152440} separator="," prefix="$" suffix=".00" duration={2.2} />
          </RainbowText>
        </AnimatedContent>
      </section>

      {/* ───────── Chapter III · The people ───────── */}
      <section className="chapter" id="people">
        <div className="people">
          <AnimatedContent distance={50} blur={soft(8)} className="chapter-head left">
            <p className="kicker">
              <span>III</span> Follow the people
            </p>
            <h2>
              One person. <em>Two findings.</em>
            </h2>
            <p className="sub">
              When an employee set up a flagged vendor, Tally pulls everything else that person touched. Findings that
              share a person are linked and escalated.
            </p>
            <ul className="lead-lines">
              <li>
                <b>Derek Holloway,</b> Accounts Payable Specialist
              </li>
              <li>
                <b>Set up</b> Apex Facility Services, paid into his own bank account
              </li>
              <li>
                <b>Set up</b> Northline Logistics, paid $38,000.00 thirteen days later
              </li>
              <li>
                <b>Requested</b> 6 invoices this quarter, every one to those two vendors
              </li>
            </ul>
          </AnimatedContent>

          <div className="graph" aria-label="Derek Holloway set up Apex Facility Services and Northline Logistics">
            <svg viewBox="0 0 600 440" aria-hidden="true">
              <defs>
                <linearGradient id="edge-a" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stopColor="var(--a1)" />
                  <stop offset="1" stopColor="var(--a3)" />
                </linearGradient>
                <linearGradient id="edge-b" x1="1" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="var(--a1)" />
                  <stop offset="1" stopColor="var(--a5)" />
                </linearGradient>
              </defs>
              <path className="edge" d="M300 120 C 230 190, 160 230, 140 320" stroke="url(#edge-a)" pathLength="1" />
              <path className="edge" d="M300 120 C 370 190, 440 230, 460 320" stroke="url(#edge-b)" pathLength="1" />
              <path className="edge dashed" d="M180 352 C 260 400, 340 400, 420 352" pathLength="1" />
            </svg>
            <div className="node person" style={{ left: '50%', top: '22%' }}>
              <span className="avatar">DH</span>
              <b>Derek Holloway</b>
              <small>E-1007 · Accounts Payable</small>
            </div>
            <div className="node vendor" style={{ left: '22%', top: '78%' }}>
              <b>Apex Facility Services</b>
              <small>V-2044 · set up 2026-05-14</small>
            </div>
            <div className="node vendor" style={{ left: '78%', top: '78%' }}>
              <b>Northline Logistics</b>
              <small>V-2045 · set up 2026-09-02</small>
            </div>
            <span className="edge-label" style={{ left: '25%', top: '45%' }}>
              pays into his account · $47,800.00
            </span>
            <span className="edge-label" style={{ left: '75%', top: '45%' }}>
              new vendor · $38,000.00 wire
            </span>
            <span className="escalate" style={{ left: '50%', top: '97%' }}>
              Connected · escalated · $85,800.00
            </span>
          </div>
        </div>
      </section>

      {/* ───────── Chapter IV · Receipts ───────── */}
      <section className="chapter" id="receipts">
        <AnimatedContent distance={60} blur={soft(10)} className="chapter-head">
          <p className="kicker">
            <span>IV</span> Receipts
          </p>
          <h2>
            Fluent words. <em>Checked facts.</em>
          </h2>
          <p className="sub">Every sentence cites the exact rows behind it. Hover a citation to open the row.</p>
        </AnimatedContent>

        <AnimatedContent className="finding" distance={50} tilt={8} duration={1.1}>
          <div className="finding-head">
            <span className="tag bad">Escalate</span>
            <span>D2 · Vendor matches an employee</span>
            <span className="finding-amt">$47,800.00</span>
          </div>
          <p className="finding-text">
            Apex Facility Services LLC <Cite id="vendors.vendors:V-2044" /> pays into bank account ••••0565, the same
            account HR holds for Derek Holloway <Cite id="hr.employees:E-1007" />. The latest of its five payments was
            $9,650.00 on 2026-09-25 <Cite id="erp.payments:PAY-300638" />.
          </p>
        </AnimatedContent>

        <div className="verifier">
          <p className="verifier-title">
            <span className="live" /> The model writes. The verifier checks.
          </p>
          <div className="claim">
            <span className="verdict ok">✓ verified</span>
            <span className="claim-text">Apex Facility Services was set up by Derek Holloway on 2026-05-14.</span>
          </div>
          <div className="claim rejected">
            <span className="verdict bad">✕ rejected</span>
            <span className="claim-text">
              Apex was paid $48,700.00 in total.
              <i className="strike" />
            </span>
            <span className="replacement">
              <small>Amount not in the cited rows. The rule’s own sentence is shown instead:</small>
              Apex Facility Services LLC was paid $47,800.00 across five payments.
            </span>
          </div>
          <div className="claim">
            <span className="verdict ok">✓ verified</span>
            <span className="claim-text">Every Apex invoice was requested by E-1007.</span>
          </div>
        </div>

        <AnimatedContent className="trust" stagger={0.08} distance={40} blur={soft(6)}>
          {TRUST.map(([title, body]) => (
            <div className="trust-item" key={title}>
              <h3>{title}</h3>
              <p>{body}</p>
            </div>
          ))}
        </AnimatedContent>
      </section>

      {/* ───────── Chapter V · Memory ───────── */}
      <section className="chapter" id="memory">
        <AnimatedContent distance={60} blur={soft(10)} className="chapter-head">
          <p className="kicker">
            <span>V</span> Memory
          </p>
          <h2>
            Explain it once. <em>It remembers.</em>
          </h2>
          <p className="sub">
            Mark a finding expected, with a reason. Next run Tally sets that pattern aside, cites your decision from
            Meterless H-MEM, and keeps flagging everything else.
          </p>
        </AnimatedContent>

        <div className="runs">
          <div className="run first">
            <p className="run-head">
              Run 1 <span>7 findings</span>
            </p>
            <ul>
              <li>
                <span className="tag bad">Flagged</span> 6 planted frauds
              </li>
              <li>
                <span className="tag warn">Flagged</span> Split shipment · V-2026 · $4,200.00
              </li>
            </ul>
            <p className="run-total">
              At risk <b>$156,640.00</b>
            </p>
          </div>

          <div className="decision">
            <small>Controller marked expected</small>
            &ldquo;Split shipment, two deliveries on one PO.&rdquo;
            <span>stored in Meterless H-MEM</span>
          </div>

          <div className="run second">
            <p className="run-head">
              Run 2 <span>6 findings</span>
            </p>
            <ul>
              <li>
                <span className="tag bad">Flagged</span> 6 planted frauds, still
              </li>
              <li className="set-aside">
                <span className="tag ok">Remembered</span> Split shipment, set aside with your reason
              </li>
            </ul>
            <p className="run-total">
              At risk{' '}
              <b>
                <CountUp
                  from={152440}
                  to={156640}
                  direction="down"
                  separator=","
                  prefix="$"
                  suffix=".00"
                  duration={2.4}
                  delay={1.6}
                />
              </b>
            </p>
          </div>
        </div>

        <div className="facts">
          {FACTS.map(fact => (
            <AnimatedContent className="fact" key={fact.label} distance={30} blur={soft(4)}>
              <RainbowText animationSpeed={9} className="fact-value">
                <CountUp
                  to={fact.to}
                  direction={fact.down ? 'down' : 'up'}
                  separator={fact.separator ?? ''}
                  duration={1.8}
                />
                {fact.suffix}
              </RainbowText>
              <span className="fact-label">{fact.label}</span>
            </AnimatedContent>
          ))}
        </div>
      </section>

      {/* ───────── Epilogue ───────── */}
      <section className="epilogue" id="epilogue">
        <div className="epilogue-ascii" aria-hidden="true">
          <RainbowASCII
            shape="eclipse"
            offset={140}
            characters=" .:-=+*#%@"
            cellSize={14}
            density={0.85}
            glow={0.6}
            flicker={0.3}
            scale={1.15}
            gradientOpacity={0.8}
            clickEffect="burst"
            variation={2}
          />
        </div>
        <AnimatedContent className="epilogue-inner" distance={40} blur={soft(12)} duration={1.2} scale={0.96}>
          <p className="kicker">
            <span>✦</span> Epilogue
          </p>
          <h2>
            Close the quarter
            <br />
            <RainbowText variant="flow" animationSpeed={9} glow={0.35}>
              with receipts.
            </RainbowText>
          </h2>
          <div className="ctas center">
            <a href="/workspace" className="btn btn-glow">
              Run an audit
            </a>
            <a href={GITHUB} className="btn btn-ghost">
              Source on GitHub
            </a>
          </div>
        </AnimatedContent>
      </section>

      <footer className="foot">
        <div>
          <b>Tally</b>. Built on Zetaris, Meterless H-MEM and NVIDIA Nemotron for the Open Agent Hackathon 2026. All
          demo data is synthetic.
        </div>
        <a href={GITHUB}>Source on GitHub</a>
      </footer>
    </div>
  );
}
