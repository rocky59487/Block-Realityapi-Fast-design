import React, { useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { cn } from '../utils/cn';

interface MagneticButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  children: React.ReactNode;
  className?: string;
  intensity?: number;
}

export const MagneticButton = React.forwardRef<HTMLButtonElement, MagneticButtonProps>(
  ({ children, className, intensity = 20, ...props }, ref) => {
    const internalRef = useRef<HTMLButtonElement>(null);
    const [position, setPosition] = useState({ x: 0, y: 0 });

    // Use internal ref for mouse tracking, but pass it to framer-motion if possible,
    // though forwardRef requires special handling.
    // Here we'll just merge the refs simply or use internal only for measuring.
    const setRefs = (element: HTMLButtonElement) => {
      internalRef.current = element;
      if (typeof ref === 'function') {
        ref(element);
      } else if (ref) {
        ref.current = element;
      }
    };

    const handleMouse = (e: React.MouseEvent<HTMLButtonElement>) => {
      if (!internalRef.current) return;
      const { clientX, clientY } = e;
      const { height, width, left, top } = internalRef.current.getBoundingClientRect();
      const middleX = clientX - (left + width / 2);
      const middleY = clientY - (top + height / 2);
      setPosition({ x: middleX * (intensity / 100), y: middleY * (intensity / 100) });
    };

    const reset = () => {
      setPosition({ x: 0, y: 0 });
    };

    // We omit framer-motion props from `props` as MagneticButtonProps might include standard HTML attrs that clash
    return (
      <motion.button
        ref={setRefs}
        onMouseMove={handleMouse}
        onMouseLeave={reset}
        animate={{ x: position.x, y: position.y }}
        transition={{ type: "spring", stiffness: 150, damping: 15, mass: 0.1 }}
        whileHover={{ scale: 1.02 }}
        whileTap={{ scale: 0.98 }}
        className={cn(
          "relative overflow-hidden rounded-full px-8 py-3 font-medium text-white",
          "glass-card glass-edge group transition-all duration-300",
          "hover:bg-white/10 hover:shadow-[0_0_20px_rgba(163,194,207,0.2)]",
          className
        )}
        {...(props as any)}
      >
        <span className="relative z-10 flex items-center gap-2">{children}</span>

        {/* Shine effect */}
        <div className="absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/10 to-transparent group-hover:animate-shine" />
      </motion.button>
    );
  }
);

MagneticButton.displayName = 'MagneticButton';
