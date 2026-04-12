import { Download, Box, Layers, Zap, Cpu, ChevronRight } from 'lucide-react';
import { motion, useScroll, useTransform } from 'framer-motion';
import { GlassCard } from '../components/GlassCard';
import { MagneticButton } from '../components/MagneticButton';
import { FadeUp, StaggerContainer, StaggerItem } from '../components/Animations';

export const Home = () => {
  const { scrollYProgress } = useScroll();
  const yHero = useTransform(scrollYProgress, [0, 1], [0, 200]);
  const opacityHero = useTransform(scrollYProgress, [0, 0.2], [1, 0]);

  const features = [
    {
      icon: <Box className="w-6 h-6 text-brand-accent/80" />,
      title: "Particle Field Simulation",
      desc: "Advanced PFSF engine providing mathematically stable, real-time structural physics computation without the overhead of traditional FEM."
    },
    {
      icon: <Cpu className="w-6 h-6 text-brand-accent/80" />,
      title: "Vulkan ML Accelerated",
      desc: "Direct GPU integration routing structural data through BIFROST ML models, predicting collapse patterns with sub-millisecond latency."
    },
    {
      icon: <Layers className="w-6 h-6 text-brand-accent/80" />,
      title: "Phase-field Fracture",
      desc: "Implement Ambati 2015 phase-field fracture mechanics natively. Materials bend, crack, and shatter based on true physical properties."
    },
    {
      icon: <Zap className="w-6 h-6 text-brand-accent/80" />,
      title: "Multigrid Solver",
      desc: "RBGS 8-color in-place smoothing solver coupled with W-Cycle multigrid ensures rapid convergence for colossal structures."
    }
  ];

  return (
    <div className="relative w-full">
      {/* --- HERO SECTION --- */}
      <section className="relative min-h-screen flex items-center justify-center pt-20 overflow-hidden">
        <motion.div
          style={{ y: yHero, opacity: opacityHero }}
          className="absolute inset-0 z-0 flex items-center justify-center pointer-events-none"
        >
          {/* Subtle grid background */}
          <div className="absolute inset-0 bg-[linear-gradient(to_right,#80808012_1px,transparent_1px),linear-gradient(to_bottom,#80808012_1px,transparent_1px)] bg-[size:24px_24px] [mask-image:radial-gradient(ellipse_60%_50%_at_50%_50%,#000_70%,transparent_100%)]" />
        </motion.div>

        <StaggerContainer className="relative z-10 max-w-5xl mx-auto px-6 text-center">
          <StaggerItem>
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-white/10 bg-white/5 backdrop-blur-sm mb-8 text-xs font-mono text-gray-400">
              <span className="w-2 h-2 rounded-full bg-brand-accent animate-pulse" />
              v1.0.0 Stable Release
            </div>
          </StaggerItem>

          <StaggerItem>
            <h1 className="text-6xl md:text-8xl font-medium tracking-tighter text-white mb-6 leading-tight">
              Redefining <span className="text-transparent bg-clip-text bg-gradient-to-r from-gray-100 to-gray-500">Structural</span>
              <br /> Physics.
            </h1>
          </StaggerItem>

          <StaggerItem>
            <p className="text-lg md:text-xl text-gray-400 max-w-2xl mx-auto mb-12 font-light leading-relaxed">
              Block Reality replaces traditional rigid voxels with a high-performance Particle Field Simulation Framework. Experience mathematically accurate material stress, fracture mechanics, and ML-accelerated collapses.
            </p>
          </StaggerItem>

          <StaggerItem>
            <div className="flex flex-col sm:flex-row items-center justify-center gap-6">
              <MagneticButton className="bg-white/10 border border-white/20">
                <Download className="w-4 h-4" />
                Download Mod
              </MagneticButton>
              <a href="#features" className="text-sm text-gray-400 hover:text-white transition-colors flex items-center gap-2 group">
                Explore Features
                <motion.span
                  className="inline-block"
                  animate={{ x: [0, 4, 0] }}
                  transition={{ repeat: Infinity, duration: 1.5, ease: "easeInOut" }}
                >
                  ↓
                </motion.span>
              </a>
            </div>
          </StaggerItem>
        </StaggerContainer>
      </section>

      {/* --- FEATURES SECTION --- */}
      <section id="features" className="py-32 relative z-10">
        <div className="max-w-7xl mx-auto px-6">
          <FadeUp>
            <div className="mb-20 md:w-2/3">
              <h2 className="text-3xl md:text-5xl font-medium tracking-tight mb-4 text-glow">Architecture of Reality</h2>
              <p className="text-gray-400 text-lg font-light">Engineered from the ground up to bypass Minecraft's limitations. Every block is an active node in a highly optimized compute shader pipeline.</p>
            </div>
          </FadeUp>

          <StaggerContainer className="grid grid-cols-1 md:grid-cols-2 gap-6" staggerDelay={0.15}>
            {features.map((feat, idx) => (
              <StaggerItem key={idx}>
                <GlassCard className="p-8 h-full flex flex-col">
                  <div className="w-12 h-12 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center mb-6">
                    {feat.icon}
                  </div>
                  <h3 className="text-xl font-medium text-gray-200 mb-3">{feat.title}</h3>
                  <p className="text-gray-400 text-sm leading-relaxed font-light flex-1">
                    {feat.desc}
                  </p>
                </GlassCard>
              </StaggerItem>
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* --- SHOWCASE SECTION --- */}
      <section id="showcase" className="py-32 relative z-10">
        <div className="max-w-7xl mx-auto px-6">
          <FadeUp>
            <div className="flex flex-col md:flex-row md:items-end justify-between mb-16 gap-6">
              <div>
                <h2 className="text-3xl md:text-5xl font-medium tracking-tight mb-4">Visual Evidence</h2>
                <p className="text-gray-400 font-light text-lg">Stress heatmaps, phase-field fractures, and systemic collapse.</p>
              </div>
              <div className="flex gap-4">
                <button className="w-10 h-10 rounded-full border border-white/10 flex items-center justify-center hover:bg-white/5 transition-colors">
                  <ChevronRight className="w-5 h-5 rotate-180" />
                </button>
                <button className="w-10 h-10 rounded-full border border-white/10 flex items-center justify-center hover:bg-white/5 transition-colors">
                  <ChevronRight className="w-5 h-5" />
                </button>
              </div>
            </div>
          </FadeUp>

          <StaggerContainer className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {[1, 2, 3].map((item) => (
              <StaggerItem key={item}>
                <GlassCard className="aspect-[4/3] p-2 flex flex-col group cursor-pointer">
                  <div className="w-full h-full bg-[#151518] rounded-xl border border-white/5 overflow-hidden relative">
                    <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent z-10" />
                    {/* Placeholder for actual mod screenshots */}
                    <div className="absolute inset-0 flex items-center justify-center text-gray-700 font-mono text-sm">
                      [ IMG_SCENE_{item}.PNG ]
                    </div>
                    <div className="absolute bottom-4 left-4 z-20 opacity-0 group-hover:opacity-100 transition-opacity duration-300">
                      <p className="text-xs font-mono text-brand-accent uppercase tracking-wider">Analysis {item}</p>
                    </div>
                  </div>
                </GlassCard>
              </StaggerItem>
            ))}
          </StaggerContainer>
        </div>
      </section>

      {/* --- REBORN SECTION --- */}
      <section id="reborn" className="py-40 relative z-10 overflow-hidden">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-brand-accent/5 via-[#0f0f11] to-[#0f0f11] z-0 pointer-events-none" />

        <div className="max-w-4xl mx-auto px-6 relative z-10 text-center">
          <FadeUp>
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-brand-accent/30 bg-brand-accent/10 mb-8 text-xs font-mono text-brand-accent">
              <span className="w-2 h-2 rounded-full bg-brand-accent" />
              CONFIDENTIAL INITIATIVE
            </div>

            <h2 className="text-4xl md:text-6xl font-medium tracking-tighter mb-6">
              Project <span className="font-mono text-transparent bg-clip-text bg-gradient-to-r from-gray-300 to-white font-normal tracking-widest">REBORN</span>
            </h2>

            <p className="text-gray-400 text-lg md:text-xl font-light mb-12 leading-relaxed">
              Block Reality was merely the foundation. Enter the beta interface of our next-generation AI modeling engine, transcending the boundaries of traditional voxel physics into pure generative structural intelligence.
            </p>

            <div className="flex justify-center">
              <MagneticButton
                className="bg-transparent border border-brand-accent/50 text-brand-accent hover:bg-brand-accent/10 w-full sm:w-auto"
                onClick={() => window.open('https://github.com/your-reborn-repo', '_blank')}
              >
                Access Beta Terminal <ChevronRight className="w-4 h-4 ml-2" />
              </MagneticButton>
            </div>
          </FadeUp>
        </div>

        {/* Decorative terminal lines */}
        <div className="absolute left-0 right-0 bottom-0 h-[1px] bg-gradient-to-r from-transparent via-white/10 to-transparent" />
        <div className="absolute left-1/2 bottom-0 w-[1px] h-24 bg-gradient-to-t from-white/10 to-transparent -translate-x-1/2" />
      </section>
    </div>
  );
};
