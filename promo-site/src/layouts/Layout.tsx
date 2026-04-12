import { Link, Outlet } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';

export const Layout = () => {
  const { scrollY } = useScroll();
  const navBackground = useTransform(
    scrollY,
    [0, 50],
    ['rgba(15, 15, 17, 0)', 'rgba(15, 15, 17, 0.7)']
  );
  const navBorder = useTransform(
    scrollY,
    [0, 50],
    ['rgba(255, 255, 255, 0)', 'rgba(255, 255, 255, 0.05)']
  );
  const navBackdropBlur = useTransform(
    scrollY,
    [0, 50],
    ['blur(0px)', 'blur(12px)']
  );

  return (
    <div className="min-h-screen flex flex-col relative text-gray-200">
      {/* Dynamic Dark Fluid Background */}
      <div className="fixed inset-0 z-[-1] overflow-hidden pointer-events-none bg-[#0f0f11]">
        <div className="absolute top-[-20%] left-[-10%] w-[50%] h-[50%] rounded-full bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-blue-900/10 via-transparent to-transparent blur-[100px] opacity-50" />
        <div className="absolute bottom-[-20%] right-[-10%] w-[50%] h-[50%] rounded-full bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-cyan-900/10 via-transparent to-transparent blur-[100px] opacity-50" />
      </div>

      <motion.nav
        style={{
          backgroundColor: navBackground,
          borderColor: navBorder,
          backdropFilter: navBackdropBlur,
          WebkitBackdropFilter: navBackdropBlur,
        }}
        className="fixed top-0 left-0 right-0 z-50 border-b border-transparent transition-all duration-300"
      >
        <div className="max-w-7xl mx-auto px-6 h-20 flex items-center justify-between">
          <Link to="/" className="text-xl font-mono tracking-tighter font-medium flex items-center gap-2 text-glow">
            <span className="w-6 h-6 rounded-sm bg-gradient-to-br from-gray-100 to-gray-400 opacity-90 inline-block" />
            BLOCK<span className="text-gray-500">REALITY</span>
          </Link>
          <div className="hidden md:flex items-center gap-8 text-sm font-medium text-gray-400">
            <Link to="#features" className="hover:text-white transition-colors">Features</Link>
            <Link to="#showcase" className="hover:text-white transition-colors">Showcase</Link>
            <Link to="#reborn" className="hover:text-brand-accent transition-colors">Reborn Beta</Link>
          </div>
        </div>
      </motion.nav>

      <main className="flex-1">
        <Outlet />
      </main>

      <footer className="mt-32 border-t border-white/5 bg-black/20 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-6 py-12 flex flex-col md:flex-row items-center justify-between gap-6">
          <div className="text-sm text-gray-500 font-mono">
            &copy; {new Date().getFullYear()} Block Reality. All rights reserved.
          </div>
          <div className="flex gap-6 text-sm text-gray-400">
            <a href="#" className="hover:text-white transition-colors">Discord</a>
            <a href="#" className="hover:text-white transition-colors">GitHub</a>
            <a href="#" className="hover:text-white transition-colors">Documentation</a>
          </div>
        </div>
      </footer>
    </div>
  );
};
