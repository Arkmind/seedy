// Medu's bridges between moods (Brand · Mascot board, "Moving between moods"). The board loops each pair there and
// back; these are its two halves, each rescaled to 0..1 over its own duration, keyframe for keyframe.
// A track moves one part of Medu (ui/medu.tsx names them) with CSS transforms around `at`, a point in the drawing's
// own units (transform-box: view-box), or around the part's own centre when `at` is missing (fill-box).
// A frame is [offset, transform, opacity, easing]; the easing runs from that frame to the next one.

export type MeduFrame = [offset: number, transform?: string | null, opacity?: number | null, easing?: string];
export interface MeduTrack { at?: [number, number]; frames: MeduFrame[] }
export interface MeduBridge { ms: number; tracks: Record<string, MeduTrack> }

export const BRIDGES: Record<string, MeduBridge> = {
  // Lost → Sleepy: the question mark droops and fades, a big yawn, heavy eyes shut, Medu sinks and settles.
  'search>sleepy': {
    ms: 2250,
    tracks: {
      eyesLook: { at: [100, 83], frames: [[0, 'scaleY(1)', 1], [0.13332, 'scaleY(1)', 1, 'ease-in-out'], [0.23112, 'scaleY(.45)', 1], [0.37776, 'scaleY(.45)', 1, 'ease-out'], [0.46668, 'scaleY(.8)', 1, 'ease-in'], [0.5422, 'scaleY(.8)', 1, 'ease-in'], [0.61332, 'scaleY(.08)', 1], [0.6222, 'scaleY(.08)', 0], [1, 'scaleY(.08)', 0]] },
      eyesClosed: { at: [100, 86], frames: [[0, 'scaleY(.6)', 0], [0.60444, 'scaleY(.6)', 0, 'ease-out'], [0.64888, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]] },
      browsLost: { at: [100, 66], frames: [[0, 'translateY(0px)', 1], [0.11112, 'translateY(0px)', 1, 'ease-in'], [0.26668, 'translateY(5px)', 0], [1, 'translateY(5px)', 0]] },
      mWobble: { at: [100, 99], frames: [[0, 'scale(1,1)', 1], [0.15556, 'scale(1,1)', 1, 'ease-in'], [0.21332, 'scale(0,1)', 0], [1, 'scale(0,1)', 0]] },
      mO: { at: [100, 99], frames: [[0, 'scale(0,0)', 0], [0.17776, 'scale(0,0)', 0, 'cubic-bezier(.2,.8,.3,1)'], [0.31112, 'scale(2.3,2.8)', 1, 'ease-in-out'], [0.49776, 'scale(2,2.4)', 1, 'ease-in-out'], [0.6222, 'scale(1,1)', 1], [1, 'scale(1,1)', 1]] },
      sweat: { at: [150, 72], frames: [[0, 'translateY(0px)', 1], [0.04444, 'translateY(0px)', 1, 'ease-in'], [0.2222, 'translateY(14px)', 0], [1, 'translateY(14px)', 0]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)'], [0.17776, 'scale(1,1)', null, 'ease-out'], [0.33332, 'scale(.95,1.08)', null, 'ease-in-out'], [0.53332, 'scale(1,1)', null, 'ease-in'], [0.77776, 'scale(1.07,.93)', null, 'ease-out'], [0.93332, 'scale(1,1)'], [1, 'scale(1,1)']] },
      tg: { at: [100, 116], frames: [[0, 'scaleY(1)'], [0.57776, 'scaleY(1)', null, 'ease-out'], [0.73332, 'scaleY(.9)', null, 'ease-in-out'], [0.88888, 'scaleY(1.04)', null, 'ease-in-out'], [1, 'scaleY(1)']] },
      q: { at: [164, 22], frames: [[0, 'rotate(0deg) translateY(0px) scale(1)', 1, 'ease-in-out'], [0.1422, 'rotate(30deg) translateY(4px) scale(1)', 1, 'ease-in'], [0.28888, 'rotate(48deg) translateY(12px) scale(0)', 0], [1, 'rotate(48deg) translateY(12px) scale(0)', 0]] },
      zs: { at: [170, 30], frames: [[0, 'scale(.6)', 0], [0.75556, 'scale(.6)', 0, 'ease-out'], [0.91112, 'scale(1)', 1], [1, 'scale(1)', 1]] },
      b: { at: [100, 130], frames: [[0, 'translateY(0px)'], [0.51112, 'translateY(0px)', null, 'cubic-bezier(.4,0,.6,1)'], [0.57776, 'translateY(-3px)', null, 'cubic-bezier(.3,0,.2,1)'], [0.8, 'translateY(13px)', null, 'ease-in-out'], [0.93332, 'translateY(10px)'], [1, 'translateY(10px)']] },
    },
  },
  // Sleepy → Lost: startled awake.
  'sleepy>search': {
    ms: 1040,
    tracks: {
      eyesLook: { at: [100, 83], frames: [[0, 'scaleY(.08)', 0], [0.14418, 'scaleY(.08)', 0], [0.16348, 'scaleY(.08)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.29805, 'scaleY(1.15)', 1, 'ease-in-out'], [0.43271, 'scaleY(1)', 1], [0.86543, 'scaleY(1)', 1, 'ease-in'], [0.92306, 'scaleY(.1)', 1, 'ease-out'], [1, 'scaleY(1)', 1]] },
      eyesClosed: { at: [100, 86], frames: [[0, 'scaleY(1)', 1], [0.12497, 'scaleY(1)', 1], [0.16348, 'scaleY(.6)', 0], [1, 'scaleY(.6)', 0]] },
      browsLost: { at: [100, 66], frames: [[0, 'translateY(-5px)', 0], [0.31727, 'translateY(-5px)', 0, 'cubic-bezier(.2,.8,.3,1)'], [0.52886, 'translateY(0px)', 1], [1, 'translateY(0px)', 1]] },
      mWobble: { at: [100, 99], frames: [[0, 'scale(0,1)', 0], [0.2019, 'scale(0,1)', 0, 'ease-out'], [0.35578, 'scale(1.2,1)', 1, 'ease-in-out'], [0.48074, 'scale(1,1)', 1], [1, 'scale(1,1)', 1]] },
      mO: { at: [100, 99], frames: [[0, 'scale(1,1)', 1], [0.10576, 'scale(1,1)', 1, 'ease-in'], [0.2019, 'scale(0,0)', 0], [1, 'scale(0,0)', 0]] },
      sweat: { at: [150, 72], frames: [[0, 'translateY(-6px)', 0], [0.67304, 'translateY(-6px)', 0, 'ease-out'], [0.96158, 'translateY(0px)', 1], [1, 'translateY(0px)', 1]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)'], [0.09615, 'scale(1,1)', null, 'ease-out'], [0.25963, 'scale(.92,1.09)', null, 'ease-in-out'], [0.57689, 'scale(1.05,.96)', null, 'ease-out'], [0.81731, 'scale(1,1)'], [1, 'scale(1,1)']] },
      tg: { at: [100, 116], frames: [[0, 'scaleY(1)'], [0.09615, 'scaleY(1)', null, 'ease-out'], [0.33656, 'scaleY(1.12)', null, 'ease-in-out'], [0.67304, 'scaleY(.94)', null, 'ease-in-out'], [0.91346, 'scaleY(1)'], [1, 'scaleY(1)']] },
      q: { at: [164, 22], frames: [[0, 'rotate(-30deg) translateY(0px) scale(0)', 0], [0.37499, 'rotate(-30deg) translateY(0px) scale(0)', 0, 'cubic-bezier(.2,.8,.3,1.3)'], [0.6058, 'rotate(8deg) translateY(0px) scale(1.25)', 1, 'ease-in-out'], [0.7981, 'rotate(0deg) translateY(0px) scale(1)', 1], [1, 'rotate(0deg) translateY(0px) scale(1)', 1]] },
      zs: { at: [170, 30], frames: [[0, 'scale(1)', 1, 'ease-in'], [0.14418, 'scale(1.3)', 0], [1, 'scale(1.3)', 0]] },
      b: { at: [100, 130], frames: [[0, 'translateY(10px)'], [0.09615, 'translateY(10px)', null, 'cubic-bezier(.15,.8,.3,1)'], [0.35578, 'translateY(-12px)', null, 'cubic-bezier(.5,0,.5,1)'], [0.76928, 'translateY(0px)'], [1, 'translateY(0px)']] },
    },
  },
  // Thinking → Yay: the dots pop, a grin, a crouch and a jump, sparkles at the top, two little hops of joy.
  'think>yay': {
    ms: 3600,
    tracks: {
      eyesUp: { at: [100, 80], frames: [[0, 'scaleY(1)', 1], [0.06945, 'scaleY(1)', 1, 'ease-in'], [0.1111, 'scaleY(.1)', 1], [0.11667, 'scaleY(.1)', 0], [1, 'scaleY(.1)', 0]] },
      eyesHappy: { at: [100, 86], frames: [[0, 'scale(.4)', 0], [0.1111, 'scale(.4)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.18056, 'scale(1.15)', 1, 'ease-in-out'], [0.22223, 'scale(1)', 1], [1, 'scale(1)', 1]] },
      mFlat: { at: [100, 98], frames: [[0, 'scaleX(1)', 1], [0.08334, 'scaleX(1)', 1, 'ease-in'], [0.11667, 'scaleX(0)', 0], [1, 'scaleX(0)', 0]] },
      mGrin: { at: [100, 100], frames: [[0, 'scale(0)', 0], [0.11667, 'scale(0)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.18888, 'scale(1.15)', 1, 'ease-in-out'], [0.22777, 'scale(1)', 1], [1, 'scale(1)', 1]] },
      blush: { at: [100, 97], frames: [[0, null, 0.45], [0.13889, null, 0.45, 'ease-out'], [0.19444, null, 0.7], [1, null, 0.7]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)'], [0.04167, 'scale(1,1)', null, 'ease-out'], [0.09722, 'scale(1.1,.88)', null, 'cubic-bezier(.2,.7,.3,1)'], [0.13889, 'scale(.92,1.1)', null, 'ease-out'], [0.18056, 'scale(1,1)', null, 'ease-in'], [0.23611, 'scale(.96,1.05)', null, 'ease-out'], [0.2639, 'scale(1.12,.86)', null, 'ease-out'], [0.31111, 'scale(.98,1.03)', null, 'ease-in-out'], [0.35556, 'scale(1.03,.97)', null, 'ease-in-out'], [0.40277, 'scale(1,1)'], [0.58333, 'scale(1,1)', null, 'ease-out'], [0.61111, 'scale(1.06,.93)', null, 'ease-out'], [0.64444, 'scale(.97,1.04)', null, 'ease-in'], [0.70556, 'scale(1.06,.93)', null, 'ease-out'], [0.75, 'scale(1,1)'], [0.83332, 'scale(1,1)', null, 'ease-out'], [0.86111, 'scale(1.05,.95)', null, 'ease-out'], [0.88888, 'scale(.98,1.03)', null, 'ease-in'], [0.94445, 'scale(1.05,.95)', null, 'ease-out'], [0.9861, 'scale(1,1)'], [1, 'scale(1,1)']] },
      tg: { at: [100, 116], frames: [[0, 'scaleY(1)'], [0.04167, 'scaleY(1)', null, 'ease-out'], [0.10556, 'scaleY(.92)', null, 'ease-out'], [0.17222, 'scaleY(1.14)', null, 'ease-in-out'], [0.22777, 'scaleY(.98)', null, 'ease-in'], [0.27778, 'scaleY(.88)', null, 'ease-out'], [0.33333, 'scaleY(1.05)', null, 'ease-in-out'], [0.38889, 'scaleY(1)'], [0.61111, 'scaleY(1)', null, 'ease-out'], [0.65554, 'scaleY(1.06)', null, 'ease-in-out'], [0.72222, 'scaleY(.94)', null, 'ease-in-out'], [0.76389, 'scaleY(1)'], [0.86111, 'scaleY(1)', null, 'ease-out'], [0.90278, 'scaleY(1.05)', null, 'ease-in-out'], [0.95833, 'scaleY(.95)', null, 'ease-in-out'], [1, 'scaleY(1)']] },
      spark0: { frames: [[0, 'scale(0) rotate(-40deg)', 0], [0.16668, 'scale(0) rotate(-40deg)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.23611, 'scale(1.3) rotate(10deg)', 1, 'ease-in-out'], [0.29166, 'scale(1) rotate(0deg)', 1], [1, 'scale(1) rotate(0deg)', 1]] },
      spark1: { frames: [[0, 'scale(0) rotate(-40deg)', 0], [0.18056, 'scale(0) rotate(-40deg)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.25, 'scale(1.3) rotate(10deg)', 1, 'ease-in-out'], [0.30555, 'scale(1) rotate(0deg)', 1], [1, 'scale(1) rotate(0deg)', 1]] },
      spark2: { frames: [[0, 'scale(0) rotate(-40deg)', 0], [0.19444, 'scale(0) rotate(-40deg)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.2639, 'scale(1.3) rotate(10deg)', 1, 'ease-in-out'], [0.31945, 'scale(1) rotate(0deg)', 1], [1, 'scale(1) rotate(0deg)', 1]] },
      spark3: { frames: [[0, 'scale(0) rotate(-40deg)', 0], [0.20833, 'scale(0) rotate(-40deg)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.27778, 'scale(1.3) rotate(10deg)', 1, 'ease-in-out'], [0.33333, 'scale(1) rotate(0deg)', 1], [1, 'scale(1) rotate(0deg)', 1]] },
      dots: { at: [100, 0], frames: [[0, 'translateY(0px) scale(1)', 1, 'ease-in'], [0.06945, 'translateY(6px) scale(.55)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.12501, 'translateY(-6px) scale(1.7)', 0], [1, 'translateY(-6px) scale(1.7)', 0]] },
      b: { at: [100, 130], frames: [[0, 'translateY(0px)'], [0.04167, 'translateY(0px)', null, 'ease-in-out'], [0.09722, 'translateY(7px)', null, 'cubic-bezier(.2,.7,.3,1)'], [0.18056, 'translateY(-34px)', null, 'cubic-bezier(.6,0,.85,.45)'], [0.2639, 'translateY(0px)', null, 'cubic-bezier(.2,.7,.4,1)'], [0.31111, 'translateY(-7px)', null, 'cubic-bezier(.5,0,.5,1)'], [0.35556, 'translateY(0px)'], [0.58333, 'translateY(0px)', null, 'cubic-bezier(.2,.7,.3,1)'], [0.64444, 'translateY(-14px)', null, 'cubic-bezier(.6,0,.8,.5)'], [0.70556, 'translateY(0px)'], [0.83332, 'translateY(0px)', null, 'cubic-bezier(.2,.7,.3,1)'], [0.88888, 'translateY(-10px)', null, 'cubic-bezier(.6,0,.8,.5)'], [0.94445, 'translateY(0px)'], [1, 'translateY(0px)']] },
    },
  },
  // Yay → Thinking: settles back.
  'yay>think': {
    ms: 600,
    tracks: {
      eyesUp: { at: [100, 80], frames: [[0, 'scaleY(.1)', 0], [0.25003, 'scaleY(.1)', 0], [0.28328, 'scaleY(.1)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.53331, 'scaleY(1.1)', 1, 'ease-in-out'], [0.74997, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]] },
      eyesHappy: { at: [100, 86], frames: [[0, 'scale(1)', 1], [0.0833, 'scale(1)', 1, 'ease-in'], [0.28328, 'scale(.4)', 0], [1, 'scale(.4)', 0]] },
      mFlat: { at: [100, 98], frames: [[0, 'scaleX(0)', 0], [0.33333, 'scaleX(0)', 0, 'ease-out'], [0.58336, 'scaleX(1)', 1], [1, 'scaleX(1)', 1]] },
      mGrin: { at: [100, 100], frames: [[0, 'scale(1)', 1], [0.03337, 'scale(1)', 1, 'ease-in'], [0.28328, 'scale(0)', 0], [1, 'scale(0)', 0]] },
      blush: { at: [100, 97], frames: [[0, null, 0.7], [0.16661, null, 0.7, 'ease-in'], [0.50006, null, 0.45], [1, null, 0.45]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)'], [0.0833, 'scale(1,1)', null, 'ease-in-out'], [0.50006, 'scale(1.04,.96)', null, 'ease-in-out'], [1, 'scale(1,1)']] },
      spark0: { frames: [[0, 'scale(1) rotate(0deg)', 1, 'ease-in'], [0.33333, 'scale(0) rotate(30deg)', 0], [1, 'scale(0) rotate(30deg)', 0]] },
      spark1: { frames: [[0, 'scale(1) rotate(0deg)', 1, 'ease-in'], [0.33333, 'scale(0) rotate(30deg)', 0], [1, 'scale(0) rotate(30deg)', 0]] },
      spark2: { frames: [[0, 'scale(1) rotate(0deg)', 1, 'ease-in'], [0.33333, 'scale(0) rotate(30deg)', 0], [1, 'scale(0) rotate(30deg)', 0]] },
      spark3: { frames: [[0, 'scale(1) rotate(0deg)', 1, 'ease-in'], [0.33333, 'scale(0) rotate(30deg)', 0], [1, 'scale(0) rotate(30deg)', 0]] },
      dots: { at: [100, 0], frames: [[0, 'translateY(4px) scale(.4)', 0], [0.41664, 'translateY(4px) scale(.4)', 0, 'cubic-bezier(.2,.8,.3,1.3)'], [0.83339, 'translateY(0px) scale(1)', 1], [1, 'translateY(0px) scale(1)', 1]] },
      b: { at: [100, 130], frames: [[0, 'translateY(0px)'], [0.0833, 'translateY(0px)', null, 'ease-in-out'], [0.50006, 'translateY(4px)', null, 'ease-in-out'], [1, 'translateY(0px)']] },
    },
  },
  // Oops → Hello: a startled jump, a wobble that flings the cable away, a smile and a wave.
  'oops>wave': {
    ms: 1050,
    tracks: {
      eyes: { at: [100, 83], frames: [[0, 'scaleY(1)', 1, 'ease-out'], [0.14286, 'scaleY(1.15)', 1, 'ease-in-out'], [0.42857, 'scaleY(1)', 1], [0.85714, 'scaleY(1)', 1, 'ease-in'], [0.92381, 'scaleY(.1)', 1, 'ease-out'], [1, 'scaleY(1)', 1]] },
      browsOops: { at: [100, 70], frames: [[0, 'translateY(0px)', 1, 'ease-out'], [0.14286, 'translateY(-4px)', 1, 'ease-in'], [0.57143, 'translateY(2px)', 0], [1, 'translateY(2px)', 0]] },
      mFrown: { at: [100, 98], frames: [[0, 'scaleY(1)', 1], [0.47619, 'scaleY(1)', 1, 'ease-in'], [0.57143, 'scaleY(0)', 0], [1, 'scaleY(0)', 0]] },
      mSmile: { at: [100, 98], frames: [[0, 'scale(0,0)', 0], [0.54286, 'scale(0,0)', 0, 'cubic-bezier(.2,.8,.3,1.4)'], [0.73333, 'scale(1.15,1.15)', 1, 'ease-in-out'], [0.85714, 'scale(1,1)', 1], [1, 'scale(1,1)', 1]] },
      blush: { at: [100, 97], frames: [[0, null, 0.4], [0.61905, null, 0.4, 'ease-out'], [0.90476, null, 0.55], [1, null, 0.55]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)', null, 'ease-out'], [0.14286, 'scale(.95,1.06)', null, 'ease-in-out'], [0.7619, 'scale(1,1)'], [1, 'scale(1,1)']] },
      plug: { at: [170, 200], frames: [[0, 'translate(0px,0px) rotate(0deg)', 1], [0.28571, 'translate(0px,0px) rotate(0deg)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.42857, 'translate(26px,-14px) rotate(50deg)', 1, 'cubic-bezier(.5,0,.9,.6)'], [0.95238, 'translate(48px,70px) rotate(150deg)', 0], [1, 'translate(48px,70px) rotate(150deg)', 0]] },
      tg: { at: [100, 116], frames: [[0, 'rotate(0deg)'], [0.2, 'rotate(0deg)', null, 'ease-in-out'], [0.31429, 'rotate(5deg)', null, 'ease-in-out'], [0.42857, 'rotate(-5deg)', null, 'ease-in-out'], [0.54286, 'rotate(3deg)', null, 'ease-in-out'], [0.67619, 'rotate(-2deg)', null, 'ease-in-out'], [0.80952, 'rotate(0deg)'], [1, 'rotate(0deg)']] },
      arm: { at: [163, 96], frames: [[0, 'rotate(70deg) scale(.6)', 0], [0.61905, 'rotate(70deg) scale(.6)', 0, 'cubic-bezier(.2,.8,.3,1.3)'], [0.90476, 'rotate(-6deg) scale(1)', 1], [1, 'rotate(-6deg) scale(1)', 1]] },
      b: { at: [100, 124], frames: [[0, 'translateY(0px) rotate(0deg)', null, 'cubic-bezier(.2,.8,.3,1)'], [0.14286, 'translateY(-6px) rotate(0deg)', null, 'ease-in-out'], [0.25714, 'translateY(-4px) rotate(-9deg)', null, 'ease-in-out'], [0.37143, 'translateY(-3px) rotate(8deg)', null, 'ease-in-out'], [0.48571, 'translateY(-2px) rotate(-5deg)', null, 'ease-in-out'], [0.6, 'translateY(-1px) rotate(3deg)', null, 'ease-in-out'], [0.73333, 'translateY(0px) rotate(0deg)'], [1, 'translateY(0px) rotate(0deg)']] },
    },
  },
  // Hello → Oops: the arm tucks away, the plug swings back.
  'wave>oops': {
    ms: 950,
    tracks: {
      browsOops: { at: [100, 70], frames: [[0, 'translateY(2px)', 0], [0.52632, 'translateY(2px)', 0, 'ease-out'], [0.78947, 'translateY(0px)', 1], [1, 'translateY(0px)', 1]] },
      mFrown: { at: [100, 98], frames: [[0, 'scaleY(0)', 0], [0.47368, 'scaleY(0)', 0, 'ease-out'], [0.63158, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]] },
      mSmile: { at: [100, 98], frames: [[0, 'scale(1,1)', 1], [0.36842, 'scale(1,1)', 1, 'ease-in'], [0.50526, 'scale(0,0)', 0], [1, 'scale(0,0)', 0]] },
      blush: { at: [100, 97], frames: [[0, null, 0.55], [0.31579, null, 0.55, 'ease-in'], [0.63158, null, 0.4], [1, null, 0.4]] },
      plug: { at: [170, 200], frames: [[0, 'translate(40px,40px) rotate(-30deg)', 0], [0.63158, 'translate(40px,40px) rotate(-30deg)', 0, 'cubic-bezier(.2,.8,.3,1.2)'], [1, 'translate(0px,0px) rotate(0deg)', 1]] },
      arm: { at: [163, 96], frames: [[0, 'rotate(-6deg) scale(1)', 1, 'ease-in'], [0.36842, 'rotate(70deg) scale(.6)', 0], [1, 'rotate(70deg) scale(.6)', 0]] },
      b: { at: [100, 124], frames: [[0, 'translateY(0px) rotate(0deg)'], [0.10526, 'translateY(0px) rotate(0deg)', null, 'ease-in-out'], [0.52632, 'translateY(5px) rotate(-3deg)', null, 'ease-in-out'], [0.94737, 'translateY(0px) rotate(0deg)'], [1, 'translateY(0px) rotate(0deg)']] },
    },
  },
  // Sleepy → Popcorn: the z’s pop, a stretch and a yawn, the eyes snap open, the bucket bounces in as Medu lands.
  'sleepy>popcorn': {
    ms: 1150,
    tracks: {
      eyesClosed: { at: [100, 86], frames: [[0, 'scaleY(1)', 1], [0.37387, 'scaleY(1)', 1], [0.4087, 'scaleY(.6)', 0], [1, 'scaleY(.6)', 0]] },
      eyesUp: { at: [100, 80], frames: [[0, 'scaleY(.08)', 0], [0.39133, 'scaleY(.08)', 0], [0.4087, 'scaleY(.08)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.54786, 'scaleY(1.15)', 1, 'ease-in-out'], [0.66956, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]] },
      mO: { at: [100, 99], frames: [[0, 'scale(1,1)', 1], [0.08695, 'scale(1,1)', 1, 'cubic-bezier(.2,.8,.3,1)'], [0.3043, 'scale(2.3,2.8)', 1, 'ease-in-out'], [0.49566, 'scale(1.8,2)', 1, 'ease-in'], [0.58261, 'scale(0,0)', 0], [1, 'scale(0,0)', 0]] },
      mChew: { at: [100, 99], frames: [[0, 'scale(0)', 0], [0.58261, 'scale(0)', 0, 'ease-out'], [0.69562, 'scale(1)', 1], [1, 'scale(1)', 1]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)', null, 'ease-out'], [0.13039, 'scale(1.06,.94)', null, 'ease-out'], [0.32175, 'scale(.9,1.12)', null, 'ease-in-out'], [0.65219, 'scale(1.07,.93)', null, 'ease-out'], [0.82609, 'scale(1,1)'], [1, 'scale(1,1)']] },
      tg: { at: [100, 116], frames: [[0, 'scaleY(1)'], [0.02606, 'scaleY(1)', null, 'ease-out'], [0.14777, 'scaleY(.93)', null, 'ease-out'], [0.37387, 'scaleY(1.14)', null, 'ease-in-out'], [0.65219, 'scaleY(.9)', null, 'ease-in-out'], [0.86961, 'scaleY(1.03)', null, 'ease-in-out'], [1, 'scaleY(1)']] },
      popcorn: { at: [100, 176], frames: [[0, 'translateY(46px) scale(.6)', 0], [0.47828, 'translateY(46px) scale(.6)', 0], [0.53041, 'translateY(38px) scale(.68)', 1, 'cubic-bezier(.2,.8,.3,1.25)'], [0.73914, 'translateY(0px) scale(1)', 1], [1, 'translateY(0px) scale(1)', 1]] },
      zs: { at: [170, 30], frames: [[0, 'scale(1)', 1, 'ease-in'], [0.14777, 'scale(1.4)', 0], [1, 'scale(1.4)', 0]] },
      b: { at: [100, 130], frames: [[0, 'translateY(10px)', null, 'cubic-bezier(.4,0,.6,1)'], [0.13039, 'translateY(13px)', null, 'cubic-bezier(.2,.75,.3,1)'], [0.39133, 'translateY(-16px)', null, 'cubic-bezier(.5,0,.5,1)'], [0.65219, 'translateY(0px)', null, 'ease-out'], [0.75652, 'translateY(2px)', null, 'ease-in-out'], [0.86961, 'translateY(0px)'], [1, 'translateY(0px)']] },
    },
  },
  // Popcorn → Sleepy: dozes off again.
  'popcorn>sleepy': {
    ms: 1400,
    tracks: {
      eyesClosed: { at: [100, 86], frames: [[0, 'scaleY(.6)', 0], [0.47856, 'scaleY(.6)', 0, 'ease-out'], [0.54998, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]] },
      eyesUp: { at: [100, 80], frames: [[0, 'scaleY(1)', 1], [0.17859, 'scaleY(1)', 1, 'ease-in-out'], [0.32144, 'scaleY(.5)', 1], [0.39286, 'scaleY(.6)', 1, 'ease-in'], [0.52144, 'scaleY(.08)', 1], [0.53571, 'scaleY(.08)', 0], [1, 'scaleY(.08)', 0]] },
      mO: { at: [100, 99], frames: [[0, 'scale(0,0)', 0], [0.46429, 'scale(0,0)', 0, 'ease-out'], [0.60714, 'scale(1,1)', 1], [1, 'scale(1,1)', 1]] },
      mChew: { at: [100, 99], frames: [[0, 'scale(1)', 1], [0.1071, 'scale(1)', 1, 'ease-in'], [0.21427, 'scale(0)', 0], [1, 'scale(0)', 0]] },
      bell: { at: [100, 124], frames: [[0, 'scale(1,1)'], [0.39286, 'scale(1,1)', null, 'ease-out'], [0.7143, 'scale(1.06,.94)', null, 'ease-out'], [0.92858, 'scale(1,1)'], [1, 'scale(1,1)']] },
      tg: { at: [100, 116], frames: [[0, 'scaleY(1)'], [0.39286, 'scaleY(1)', null, 'ease-out'], [0.67856, 'scaleY(.9)', null, 'ease-in-out'], [0.8929, 'scaleY(1.03)'], [1, 'scaleY(1)']] },
      popcorn: { at: [100, 176], frames: [[0, 'translateY(0px) scale(1)', 1, 'ease-in-out'], [0.07142, 'translateY(-5px) scale(1.03)', 1, 'cubic-bezier(.5,0,.9,.5)'], [0.20714, 'translateY(44px) scale(.78)', 1], [0.25002, 'translateY(52px) scale(.7)', 0], [1, 'translateY(52px) scale(.7)', 0]] },
      zs: { at: [170, 30], frames: [[0, 'scale(.6)', 0], [0.67856, 'scale(.6)', 0, 'ease-out'], [0.92858, 'scale(1)', 1], [1, 'scale(1)', 1]] },
      b: { at: [100, 130], frames: [[0, 'translateY(0px)'], [0.03568, 'translateY(0px)', null, 'ease-in-out'], [0.39286, 'translateY(-3px)', null, 'cubic-bezier(.3,0,.2,1)'], [0.74998, 'translateY(12px)', null, 'ease-in-out'], [0.92858, 'translateY(10px)'], [1, 'translateY(10px)']] },
    },
  },
};
