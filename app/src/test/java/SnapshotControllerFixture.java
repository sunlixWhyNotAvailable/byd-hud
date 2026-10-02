public class SnapshotControllerFixture {
    public int calls;
    private boon pending;
    public void L(boon callback) { calls++; pending = callback; }
    public void respond() { boon callback = pending; pending = null; callback.a(null); }
}
