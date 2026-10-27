import { useRef, useState, useEffect } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';
import AeroShards from './components/AeroShards.jsx';

gsap.registerPlugin(ScrollTrigger, useGSAP);

// Theme tokens GSAP tweens between. Keep in sync with :root in styles.css.
const LIGHT = {
  '--bg': '#fbfaf8',
  '--fg': '#15131c',
  '--muted': '#5f5b6b',
  '--line': 'rgba(21, 19, 28, 0.09)',
  '--card': 'rgba(255, 255, 255, 0.66)',
  '--nav': 'rgba(251, 250, 248, 0.72)'
};
const DARK = {
  '--bg': '#0c0b12',
  '--fg': '#f3f1f8',
  '--muted': '#a29db3',
  '--line': 'rgba(243, 241, 248, 0.1)',
  '--card': 'rgba(255, 255, 255, 0.04)',
  '--nav': 'rgba(12, 11, 18, 0.66)'
};

const GAPS = [
  {
    tag: 'In place',
    title: 'Reads your books where they live',
    body: 'ERP, vendor master, HR and bank feed stay where they are. Tally queries all four through Zetaris, so nothing is exported, copied or uploaded first.'
  },
  {
    tag: 'Every row',
    title: 'Checks everything, not a sample',
    body: 'Every invoice, payment and bank line is checked against your own approval policy, read from the ERP instead of hard-coded.'
  },
  {
    tag: 'Memory',
    title: 'Knows what you cleared last time',
    body: 'Mark a finding expected once, with a reason. Next run Tally sets it aside and cites your decision, kept in Meterless H-MEM with its full history.'
  },
  {
    tag: 'Receipts',
    title: 'Every sentence cites its rows',
    body: 'Click any claim and the exact row opens. Each finding downloads as an evidence pack with the rows and the SQL that found it.'
  }
];

const STEPS = [
  {
    word: 'Read',
    body: 'One SQL door. Zetaris joins the ERP, the vendor master, HR and the bank feed in place, and every statement shows on screen.'
  },
  {
    word: 'Check',
    body: 'Six checks: duplicate payments, vendors that match an employee, invoices split to dodge approval, bank payments missing from the books, new vendors paid big, and self-approval.'
  },
  {
    word: 'Follow',
    body: 'When an employee set up a flagged vendor, Tally pulls everything else that person touched, and connects findings that share a person.'
  },
  {
    word: 'Remember',
    body: 'Your past decisions come back from Meterless H-MEM. A pattern you already explained is set aside, with your reason and the memory id.'
  }
];

const PRIVACY = [
  ['Rules decide', 'Every verdict comes from a plain rule over the rows. A model never decides what is fraud.'],
  ['Checked words', 'Nemotron words the explanation. Each sentence is checked against the rows it cites, or the rule’s own sentence is shown.'],
  ['Masked', 'Bank account numbers leave the server as their last four digits.'],
  ['Traceable', 'Every finding that was set aside points to the person who cleared it, and why.']
];

const FACTS = [
  { value: 6, label: 'of 6 planted frauds found in the demo books' },
  { value: 0, label: 'other false alarms across 2,709 records' },
  { value: 4, label: 'systems read in place, in one audit' },
  { value: 6, label: 'checks, each one SQL plus a plain rule' }
];

const MARQUEE = [
  'Read in place',
  'Every row checked',
  'Rules decide, not models',
  'Every sentence cites its rows',
  'Remembers your decisions',
  'Zetaris',
  'Meterless H-MEM',
  'NVIDIA Nemotron'
];

function useNarrow() {
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 860px)').matches);
  useEffect(() => {
    const query = window.matchMedia('(max-width: 860px)');
    const update = () => setNarrow(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  return narrow;
}

// Weaker laptops (few cores or little memory) render the same glass background with fewer shards and a lower
// pixel ratio, so it stays smooth. Nothing is removed; it just does less work.
const LITE = (navigator.hardwareConcurrency || 8) <= 4 || (navigator.deviceMemory || 8) <= 4;
const DPR = LITE ? 1 : 1.5;
const fewer = (n) => (LITE ? Math.round(n * 0.6) : n);

export default function App() {
  const root = useRef(null);
  const narrow = useNarrow();
  const [heroGpu, setHeroGpu] = useState(true);
  const [darkGpu, setDarkGpu] = useState(true);

  useGSAP(
    () => {
      const html = document.documentElement;
      gsap.set(html, LIGHT);

      const mm = gsap.matchMedia();

      // Theme: light → dark through "How it works" and "Privacy", then back to light.
      const toTheme = (tokens, dark) => {
        gsap.to(html, { ...tokens, duration: 0.9, ease: 'power2.out', overwrite: 'auto' });
        gsap.to('.dark-shards', { autoAlpha: dark ? 1 : 0, duration: 1.1, ease: 'power2.out', overwrite: 'auto' });
      };

      ScrollTrigger.create({
        trigger: '.dark-zone',
        start: 'top 55%',
        end: 'bottom 45%',
        onEnter: () => toTheme(DARK, true),
        onEnterBack: () => toTheme(DARK, true),
        onLeave: () => toTheme(LIGHT, false),
        onLeaveBack: () => toTheme(LIGHT, false)
      });

      mm.add('(prefers-reduced-motion: no-preference)', () => {
        // Hero intro
        const intro = gsap.timeline({ defaults: { ease: 'expo.out' } });
        intro
          .from('.nav', { y: -24, autoAlpha: 0, duration: 1 })
          .from('.hero .eyebrow', { y: 14, autoAlpha: 0, duration: 0.9 }, '-=0.6')
          .from('.hero h1 .line > span', { yPercent: 110, duration: 1.3, stagger: 0.12 }, '-=0.7')
          .from('.hero .lede', { y: 18, autoAlpha: 0, duration: 1 }, '-=0.9')
          .from('.hero .ctas > *', { y: 14, autoAlpha: 0, duration: 0.8, stagger: 0.08 }, '-=0.8')
          .from('.hero .hint', { autoAlpha: 0, duration: 1 }, '-=0.4')
          .from(
            '.floor-card',
            { y: 40, autoAlpha: 0, rotateX: 8, scale: 0.97, duration: 1.4, transformOrigin: '50% 100%' },
            '-=1.2'
          )
          .from('.floor-card .mini', { y: 12, autoAlpha: 0, duration: 0.7, stagger: 0.07 }, '-=0.9');

        // Hero drifts up and fades a little as you leave it
        gsap.to('.hero-inner', {
          yPercent: -8,
          autoAlpha: 0.35,
          ease: 'none',
          scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true }
        });

        // Gentle float on the floor card
        gsap.to('.floor-card', { y: -8, duration: 3.2, ease: 'sine.inOut', yoyo: true, repeat: -1, delay: 1.6 });

        // Marquee
        const track = root.current.querySelector('.marquee-track');
        gsap.to(track, { xPercent: -50, duration: 38, ease: 'none', repeat: -1 });

        // Section headings
        gsap.utils.toArray('.reveal').forEach(element => {
          gsap.from(element, {
            y: 28,
            autoAlpha: 0,
            duration: 1.1,
            ease: 'power3.out',
            scrollTrigger: { trigger: element, start: 'top 85%' }
          });
        });

        // Cards arrive in a soft stagger
        gsap.set('.gap-card', { autoAlpha: 0 });
        ScrollTrigger.batch('.gap-card', {
          start: 'top 88%',
          onEnter: batch =>
            gsap.fromTo(
              batch,
              { y: 36, autoAlpha: 0 },
              { y: 0, autoAlpha: 1, duration: 1, ease: 'power3.out', stagger: 0.09, overwrite: true }
            )
        });

        // Steps: the rail fills as you scroll, each step lights up as the fill reaches it
        gsap.fromTo(
          '.rail-fill',
          { scaleY: 0 },
          {
            scaleY: 1,
            ease: 'none',
            scrollTrigger: { trigger: '.steps', start: 'top 70%', end: 'bottom 60%', scrub: 0.6 }
          }
        );
        gsap.utils.toArray('.step').forEach(step => {
          gsap.fromTo(
            step,
            { autoAlpha: 0.25, x: 12 },
            {
              autoAlpha: 1,
              x: 0,
              duration: 0.8,
              ease: 'power2.out',
              scrollTrigger: { trigger: step, start: 'top 68%', toggleActions: 'play none none reverse' }
            }
          );
        });

        // Privacy statements
        gsap.set('.privacy-item', { autoAlpha: 0 });
        ScrollTrigger.batch('.privacy-item', {
          start: 'top 88%',
          onEnter: batch =>
            gsap.fromTo(
              batch,
              { y: 24, autoAlpha: 0 },
              { y: 0, autoAlpha: 1, duration: 0.9, ease: 'power3.out', stagger: 0.08, overwrite: true }
            )
        });

        // Counters
        gsap.utils.toArray('.fact-value').forEach(element => {
          const end = Number(element.dataset.value);
          const counter = { value: end === 0 ? 9 : 0 };
          gsap.to(counter, {
            value: end,
            duration: 1.6,
            ease: 'power3.out',
            snap: { value: 1 },
            onUpdate: () => {
              element.textContent = counter.value;
            },
            scrollTrigger: { trigger: element, start: 'top 85%' }
          });
        });

        // Insight lines type in one after another
        gsap.from('.insight-line', {
          autoAlpha: 0,
          x: -10,
          duration: 0.7,
          stagger: 0.18,
          ease: 'power2.out',
          scrollTrigger: { trigger: '.insights-panel', start: 'top 75%' }
        });

        // Final call to action
        gsap.from('.final h2 .line > span', {
          yPercent: 110,
          duration: 1.2,
          stagger: 0.1,
          ease: 'expo.out',
          scrollTrigger: { trigger: '.final', start: 'top 75%' }
        });
      });

      return () => mm.revert();
    },
    { scope: root }
  );

  return (
    <div ref={root} className="page">
      <nav className="nav">
        <a href="/" className="brand">
          Tally<span className="dot" />
        </a>
        <div className="nav-links">
          <a href="#how">How it works</a>
          <a href="#trust">Trust</a>
          <a href="https://github.com/sidharthnair7/Tally">GitHub</a>
        </div>
        <a href="/workspace" className="btn btn-dark btn-sm">
          Open the workspace
        </a>
      </nav>

      <header className="hero">
        <div className="hero-bg" aria-hidden="true">
          <div className="aurora" />
          {heroGpu && (
            <AeroShards
              lightMode
              shardColor="#8fa2ff"
              accentColor="#ff8cc6"
              placement={narrow ? 'full' : 'right'}
              material="glass"
              markers="plus"
              count={fewer(narrow ? 700 : 1200)}
              dpr={DPR}
              depthOfField={0.7}
              vignette={0.25}
              grain={0.04}
              mouseStrength={0.55}
              parallax={0.45}
              onError={error => { console.warn('AeroShards (hero) fell back:', error); setHeroGpu(false); }}
            />
          )}
          <div className="hero-scrim" />
        </div>

        <div className="hero-inner">
          <div className="hero-copy">
            <div className="eyebrow">
              <span className="pulse" /> For the controller with no audit team
            </div>
            <h1>
              <span className="line">
                <span>
                  Find the money that <em className="rainbow-text">shouldn&rsquo;t have left.</em>
                </span>
              </span>
              <span className="line muted">
                <span>Tally knows what you cleared last time.</span>
              </span>
            </h1>
            <p className="lede">
              Tally reads your ERP, vendor master, HR system and bank feed where they live, checks every payment
              against your own approval policy, follows the people behind each finding, and shows you the exact rows
              that prove it.
            </p>
            <div className="ctas">
              <a href="/workspace" className="btn btn-rainbow">
                Run an audit
              </a>
              <a href="#how" className="btn btn-ghost">
                See how it works
              </a>
            </div>
            {heroGpu && <p className="hint">Click the glass to send a pulse. Hold to gather it.</p>}
          </div>

          <div className="floor-card" aria-label="Example of an audit result">
            <div className="floor-card-head">
              <span className="live" /> Audit · Q3 2026 books
            </div>
            <div className="minis">
              <div className="mini overdue">
                <b>Escalate</b>
                <span>$47,800.00</span>
                <small>Vendor paid into an AP clerk&rsquo;s own account</small>
              </div>
              <div className="mini ok">
                <b>Cleared by the books</b>
                <span>Disclosed</span>
                <small>Conflict of interest approved by the Controller</small>
              </div>
              <div className="mini bussing">
                <b>Remembered</b>
                <span>Split shipment</span>
                <small>You cleared this last run</small>
              </div>
              <div className="mini wait">
                <b>At risk</b>
                <span className="big">$152k</span>
                <small>6 findings, every one with its rows</small>
              </div>
            </div>
          </div>
        </div>
      </header>

      <div className="marquee" aria-hidden="true">
        <div className="marquee-track">
          {[...MARQUEE, ...MARQUEE].map((item, index) => (
            <span key={index}>
              {item}
              <i />
            </span>
          ))}
        </div>
      </div>

      <section className="section">
        <div className="section-head reveal">
          <p className="kicker">Why Tally</p>
          <h2>
            An auditor that <em>learns your business.</em>
          </h2>
        </div>
        <div className="gaps">
          {GAPS.map(gap => (
            <article className="gap-card" key={gap.title}>
              <span className="tag">{gap.tag}</span>
              <h3>{gap.title}</h3>
              <p>{gap.body}</p>
            </article>
          ))}
        </div>
      </section>

      <div className="dark-zone">
        <div className="dark-shards" aria-hidden="true">
          {darkGpu && (
            <AeroShards
              shardColor="#7c6cf0"
              accentColor="#ff7ad9"
              placement="left"
              material="glass"
              markers="dots"
              count={fewer(narrow ? 450 : 800)}
              dpr={DPR}
              depthOfField={0.8}
              vignette={0.45}
              glow={0.4}
              speed={0.7}
              mouseStrength={0.45}
              parallax={0.35}
              onError={error => { console.warn('AeroShards (dark) fell back:', error); setDarkGpu(false); }}
            />
          )}
        </div>

        <section className="section how" id="how">
          <div className="how-grid">
            <div className="section-head sticky reveal">
              <p className="kicker">How it works</p>
              <h2>
                Read. Check. Follow. <em>Remember.</em>
              </h2>
              <p className="sub">
                The SQL finds candidates; a plain rule decides. Every step and every query is shown live while the
                audit runs.
              </p>
            </div>
            <div className="steps">
              <div className="rail" aria-hidden="true">
                <div className="rail-fill" />
              </div>
              <ol>
              {STEPS.map((step, index) => (
                <li className="step" key={step.word}>
                  <span className="step-num">0{index + 1}</span>
                  <div>
                    <h3>{step.word}</h3>
                    <p>{step.body}</p>
                  </div>
                </li>
              ))}
              </ol>
            </div>
          </div>
        </section>

        <section className="section" id="trust">
          <div className="section-head reveal">
            <p className="kicker">Trust by design</p>
            <h2>
              Fluent words, <em>checked facts.</em>
            </h2>
          </div>
          <div className="privacy">
            {PRIVACY.map(([title, body]) => (
              <div className="privacy-item" key={title}>
                <h3>{title}</h3>
                <p>{body}</p>
              </div>
            ))}
          </div>
        </section>
      </div>

      <section className="section insights">
        <div className="insights-grid">
          <div className="section-head reveal">
            <p className="kicker">Connected findings</p>
            <h2>
              One person, <em>two findings.</em>
            </h2>
            <p className="sub">
              Findings that share a person are linked and escalated, and Tally follows the lead: every vendor that
              person set up and every invoice they requested.
            </p>
          </div>
          <div className="insights-panel reveal">
            <div className="panel-head">
              <span>Lead</span>
              <span className="chip">Demo books</span>
            </div>
            <p className="insight-line">
              <b>Derek Holloway,</b> Accounts Payable Specialist
            </p>
            <p className="insight-line">
              <b>Set up</b> Apex Facility Services, paid into his own bank account
            </p>
            <p className="insight-line">
              <b>Set up</b> Northline Logistics, paid $38,000.00 thirteen days later
            </p>
            <p className="insight-line">
              <b>Requested</b> 6 invoices this quarter, every one to those two vendors
            </p>
          </div>
        </div>

        <div className="facts">
          {FACTS.map(fact => (
            <div className="fact reveal" key={fact.label}>
              <span className="fact-value" data-value={fact.value}>
                {fact.value}
              </span>
              <span className="fact-label">{fact.label}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="final">
        <div className="final-glow" aria-hidden="true" />
        <h2>
          <span className="line">
            <span>Close the quarter</span>
          </span>
          <span className="line">
            <span>
              with <em className="rainbow-text">receipts.</em>
            </span>
          </span>
        </h2>
        <div className="ctas center">
          <a href="/workspace" className="btn btn-rainbow">
            Run an audit
          </a>
          <a href="https://github.com/sidharthnair7/Tally" className="btn btn-ghost">
            Source on GitHub
          </a>
        </div>
      </section>

      <footer className="foot">
        <div>
          <b>Tally</b>. Built on Zetaris, Meterless H-MEM and NVIDIA Nemotron for the Open Agent Hackathon 2026. All demo data is synthetic.
        </div>
        <a href="https://github.com/sidharthnair7/Tally">Source on GitHub</a>
      </footer>
    </div>
  );
}
