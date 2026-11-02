package com.bydhud.mapshud;

import java.util.Map;
import java.util.Set;

/** Selected-route style IDs from aiqw, including its paired route variants. */
final class HudPalette {
    static final int[] NAMED_ROUTES={6,7,8,1244,1245,1246,1495,1496,1497,1500,1501,1502,
            1605,1606,1607,1616,1617,1618,1612,1613,1614,1621,1622,1623,
            1762,1763,1764,1753,1754,1755,1756,1757,1758,1759,1760,1761};
    static boolean selectedRoute(int id) {
        for(int named:NAMED_ROUTES)if(id==named)return true;
        return false;
    }
    static int routeName(int named,long global,Set<Integer> segments,Map<Long,Integer> globals) {
        if(named!=0)return selectedRoute(named)||segments.contains(named)?named:0;
        // Local IDs never enter this lookup; globals belong to the active legend, not cnax.
        Integer resolved=global==-1?null:globals.get(global);
        return resolved==null?0:resolved;
    }
    static int blueLevel(int argb) {
        int r=(argb>>>16)&255,g=(argb>>>8)&255,b=argb&255;
        return (argb>>>24)!=0 && b>r+20 && b>=g ? b : 0;
    }
    static int routeColor(int argb,int peak) {
        return routeColor(argb,peak,0xff66b5ff);
    }
    static int routeColor(int argb,int peak,int target) {
        int level=blueLevel(argb);
        if(level==0 || peak==0)return argb;
        float scale=Math.min(1f,level/(float)peak);
        return (argb&0xff000000)|(Math.round(((target>>>16)&255)*scale)<<16)
                |(Math.round(((target>>>8)&255)*scale)<<8)|Math.round((target&255)*scale);
    }
}
