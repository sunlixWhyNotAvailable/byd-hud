package com.bydhud.mapshud;

/** Fill colors of the pinned 26.30 position textures, not a map-wide color filter. */
final class MarkerPalette {
    static final int AMBER = 0xffb81a;

    static boolean haloTexture(int id) {
        switch(id) {
            case 0x7f080302: // blue_dot_pulsate
            case 0x7f080303: // blue_dot_pulsate_map_colors2
            case 0x7f080a2f: // new_blue_dot_glow
            case 0x7f080a30: // new_blue_dot_glow_map_colors2
            case 0x7f08038b: // chevron_navigation_disc
            case 0x7f08038c: // chevron_navigation_disc_min_mode
            case 0x7f08038d: // chevron_navigation_disc_night
            case 0x7f08038e: // chevron_navigation_disc_night_ambient
            case 0x7f08038f: return true; // chevron_navigation_disc_night_map_colors2
            default: return false;
        }
    }

    static boolean accuracyHaloColor(int id) {
        return id==0x7f060d5d || id==0x7f060d5e; // new_location_accuracy_fill / line
    }
    static int accuracyOutline(boolean hud,int id,int color) {
        return hud && accuracyHaloColor(id) ? 0 : color;
    }

    static int sourceFill(int id) {
        switch(id) {
            case 0x7f080a2e: // new_blue_dot
            case 0x7f080a31: // new_direction_pointer
            case 0x7f080384: // chevron_navigation_chevron
            case 0x7f0802e6: return 0x4285f4; // bearingless_navigation_chevron
            case 0x7f080386: // chevron_navigation_chevron_map_colors2
            case 0x7f0802e7: return 0x0f53ff; // bearingless_navigation_chevron_map_colors2
            case 0x7f080388: // chevron_navigation_chevron_night
            case 0x7f0802e9: return 0xdff6ff; // bearingless_navigation_chevron_night
            case 0x7f08038a: return 0xdfebff; // chevron_navigation_chevron_night_map_colors2
            case 0x7f0802ea: return 0xd5ddfc; // bearingless_navigation_chevron_night_map_colors2
            case 0x7f080385: return 0x893eff; // chevron_navigation_chevron_ambient
            case 0x7f080389: return 0xf6f8fb; // chevron_navigation_chevron_night_ambient
            default: return 0; // Gray stale/minimum-mode marker fills retain stock meaning.
        }
    }

    static int recolor(int id,int[] pixels) {
        return recolor(id,pixels,AMBER);
    }
    static int recolor(int id,int[] pixels,int target) {
        if(haloTexture(id)) {
            int changed=0;
            for(int i=0;i<pixels.length;i++) {
                if(pixels[i]!=0){pixels[i]=0;changed++;}
            }
            return changed;
        }
        int fill=sourceFill(id);
        if(fill==0)return 0;
        int baseR=fill>>>16,baseB=fill&255,changed=0;
        for(int i=0;i<pixels.length;i++) {
            int p=pixels[i],r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;
            if((p>>>24)==0 || b<=r || b<=g)continue;
            // Separate colored fill from neutral shading/white antialiasing, including pale night PNGs.
            float colored=(b-r)/(float)(baseB-baseR),neutral=r-colored*baseR;
            int out=(p&0xff000000) | (channel(colored*((target>>>16)&255)+neutral)<<16)
                    | (channel(colored*((target>>>8)&255)+neutral)<<8) | channel(colored*(target&255)+neutral);
            if(out!=p){pixels[i]=out;changed++;}
        }
        return changed;
    }

    private static int channel(float value) { return Math.max(0,Math.min(255,Math.round(value))); }
}
