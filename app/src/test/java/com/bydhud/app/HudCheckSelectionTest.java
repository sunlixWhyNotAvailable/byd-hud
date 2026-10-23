package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class HudCheckSelectionTest {
    @Test public void channelProfilesAndSavedAddSomeIpPreference() {
        HudCheckState basic = new HudCheckState();
        assertEquals(HudCheckState.Channel.BASIC, basic.channel);
        assertTrue(basic.usesSomeIp());
        assertTrue(basic.usesInstrument());
        assertTrue(basic.usesAmap());
        assertFalse(basic.usesClusterDebug());
        assertFalse(basic.effectiveAddSomeIp());

        HudCheckState saved = basic.selectChannel(HudCheckState.Channel.INSTRUMENT)
                .selectAddSomeIp(true).selectChannel(HudCheckState.Channel.BASIC);
        assertTrue(saved.addSomeIp);
        assertFalse(saved.effectiveAddSomeIp());
        assertTrue(saved.selectChannel(HudCheckState.Channel.INSTRUMENT).effectiveAddSomeIp());

        HudCheckState someIp = basic.selectChannel(HudCheckState.Channel.SOMEIP);
        assertTrue(someIp.usesSomeIp());
        assertFalse(someIp.usesInstrument());
        assertFalse(someIp.usesClusterDebug());
        assertFalse(someIp.usesAmap());

        HudCheckState instrument = basic.selectChannel(HudCheckState.Channel.INSTRUMENT);
        assertFalse(instrument.usesSomeIp());
        assertTrue(instrument.usesInstrument());
        assertFalse(instrument.usesClusterDebug());
        assertFalse(instrument.usesAmap());
        assertTrue(instrument.selectAddSomeIp(true).usesSomeIp());

        HudCheckState clusterDebug = basic.selectChannel(HudCheckState.Channel.CLUSTER_DEBUG);
        assertFalse(clusterDebug.usesSomeIp());
        assertFalse(clusterDebug.usesInstrument());
        assertTrue(clusterDebug.usesClusterDebug());
        assertFalse(clusterDebug.usesAmap());
        assertTrue(clusterDebug.selectAddSomeIp(true).usesSomeIp());

        HudCheckState amap = basic.selectChannel(HudCheckState.Channel.AMAP);
        assertFalse(amap.usesSomeIp());
        assertFalse(amap.usesInstrument());
        assertFalse(amap.usesClusterDebug());
        assertTrue(amap.usesAmap());
        assertTrue(amap.selectAddSomeIp(true).usesSomeIp());
    }

    @Test public void sharedSelectionsPersistAndBasicExtendedSwitchKeepsRun() throws Exception {
        HudCheckState state = new HudCheckState()
                .selectFormat(HudCheckState.Format.C)
                .selectCodeTable(HudCheckState.CodeTable.HUAWEI)
                .selectChannel(HudCheckState.Channel.AMAP)
                .selectAddSomeIp(true)
                .selectDenzaOem(true)
                .toggleRun();
        HudCheckState extended = state.selectMode(HudCheckState.Mode.EXTENDED);

        assertTrue(extended.running);
        assertTrue(extended.isDenzaOem());
        assertEquals(HudCheckState.Format.C, extended.format);
        assertEquals(HudCheckState.CodeTable.HUAWEI, extended.codeTable);
        assertEquals(HudCheckState.Channel.AMAP, extended.channel);
        assertTrue(extended.addSomeIp);
        assertTrue(extended.effectiveAddSomeIp());
        assertEquals(extended, HudCheckState.restore(extended.checkpoint()));
        assertEquals(extended.hashCode(), HudCheckState.restore(extended.checkpoint()).hashCode());

        assertSame(extended, extended.selectBasicFormat(HudCheckState.Format.A));
        assertSame(extended, extended.selectFormat(HudCheckState.Format.A));
        assertEquals(extended, extended.selectCodeTable(HudCheckState.CodeTable.BYD));
        assertEquals(extended, extended.tick());
        assertEquals(extended, extended.stepExtended(1));

        HudCheckState normal = extended.selectDenzaOem(false);
        assertFalse(normal.isDenzaOem());
        HudCheckState changedFormat = normal.selectFormat(HudCheckState.Format.A);
        HudCheckState changedTable = changedFormat.selectCodeTable(HudCheckState.CodeTable.BYD);
        assertTrue(changedTable.running);
        assertEquals(HudCheckState.Format.A, changedTable.format);
        assertEquals(HudCheckState.CodeTable.BYD, changedTable.codeTable);
        assertEquals(changedTable, changedTable.selectMode(HudCheckState.Mode.BASIC)
                .selectMode(HudCheckState.Mode.EXTENDED));

        HudCheckState paused = changedTable.selectDenzaOem(true);
        assertEquals(paused.extendedIndex, paused.tick().extendedIndex);
        assertEquals(paused.extendedIndex, paused.stepExtended(1).extendedIndex);
        assertEquals(paused, HudCheckState.restore(paused.checkpoint()));
        HudCheckState shanghai = paused.selectMode(HudCheckState.Mode.SHANGHAI);
        assertFalse(shanghai.isDenzaOem());
        assertFalse(shanghai.usesSomeIp());
        assertFalse(shanghai.usesInstrument());
        assertFalse(shanghai.usesClusterDebug());
        assertFalse(shanghai.usesAmap());
    }

    @Test public void checkpointDefaultsNewSelectionsAndEqualityIncludesThem() throws Exception {
        JSONObject oldCheckpoint = new HudCheckState().checkpoint();
        oldCheckpoint.remove("channel");
        oldCheckpoint.remove("addSomeIp");
        oldCheckpoint.remove("denzaOem");
        HudCheckState restored = HudCheckState.restore(oldCheckpoint);
        assertEquals(HudCheckState.Channel.BASIC, restored.channel);
        assertFalse(restored.addSomeIp);
        assertFalse(restored.denzaOem);

        HudCheckState base = new HudCheckState();
        assertNotEquals(base, base.selectChannel(HudCheckState.Channel.SOMEIP));
        assertNotEquals(base, base.selectAddSomeIp(true));
        assertNotEquals(base, base.selectDenzaOem(true));
    }

    @Test public void writerChangesOnlyWhenEffectiveRouteOrOemModeChanges() {
        HudCheckState basic = new HudCheckState();
        assertFalse(HudCheckChannelPolicy.changesWriter(basic, basic.selectAddSomeIp(true)));

        HudCheckState someIp = basic.selectChannel(HudCheckState.Channel.SOMEIP);
        assertFalse(HudCheckChannelPolicy.changesWriter(someIp, someIp.selectAddSomeIp(true)));

        HudCheckState instrument = basic.selectChannel(HudCheckState.Channel.INSTRUMENT);
        assertTrue(HudCheckChannelPolicy.changesWriter(instrument,
                instrument.selectAddSomeIp(true)));
        assertTrue(HudCheckChannelPolicy.changesWriter(instrument,
                instrument.selectDenzaOem(true)));

        HudCheckState extended = basic.selectMode(HudCheckState.Mode.EXTENDED);
        assertFalse(HudCheckChannelPolicy.changesWriter(basic, extended));
        assertFalse(HudCheckChannelPolicy.changesWriter(extended,
                extended.selectMode(HudCheckState.Mode.BASIC)));
    }

    @Test public void unsupportedExtendedStagesRemainUnsupportedUnlessRouteCoversThem() {
        HudCheckState extended = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .withAutomatic(false);
        HudCheckState mapPath = extended.stepExtended(15 - extended.extendedIndex);
        HudCheckState navigationMap = extended.stepExtended(16 - extended.extendedIndex);

        for (HudCheckState.Channel channel : new HudCheckState.Channel[] {
                HudCheckState.Channel.INSTRUMENT,
                HudCheckState.Channel.AMAP,
                HudCheckState.Channel.CLUSTER_DEBUG
        }) {
            HudCheckState mapSample = channel == HudCheckState.Channel.AMAP
                    ? navigationMap : mapPath;
            assertFalse(HudCheckChannelPolicy.supportsExtendedStage(
                    mapSample.selectChannel(channel)));
            assertTrue(HudCheckChannelPolicy.supportsExtendedStage(
                    mapSample.selectChannel(channel).selectAddSomeIp(true)));
        }

        HudCheckState routeLanes = extended.stepExtended(21 - extended.extendedIndex)
                .selectChannel(HudCheckState.Channel.INSTRUMENT);
        assertTrue(HudCheckChannelPolicy.supportsExtendedStage(routeLanes));
        assertFalse(HudCheckChannelPolicy.supportsExtendedStage(
                routeLanes.selectChannel(HudCheckState.Channel.AMAP)));

        HudCheckState liveScenario = extended.stepExtended(
                HudCheckLiveScenario.FIRST_EXTENDED_INDEX - extended.extendedIndex);
        assertTrue(HudCheckChannelPolicy.supportsExtendedStage(
                liveScenario.selectChannel(HudCheckState.Channel.INSTRUMENT)));
        assertTrue(HudCheckChannelPolicy.supportsExtendedStage(
                liveScenario.selectChannel(HudCheckState.Channel.AMAP)));
        assertTrue(HudCheckChannelPolicy.supportsExtendedStage(
                mapPath.selectDenzaOem(true)));
        assertNotEquals(HudCheckDiagnostics.sampleKey(extended),
                HudCheckDiagnostics.sampleKey(extended.withStreetTestGuidePoint(true)));
    }
}
