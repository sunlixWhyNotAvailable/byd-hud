package com.bydhud.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Carries the private Instrument proxy Binder in one explicit broadcast. */
public final class InstrumentProxyBinder implements Parcelable {
    public static final Creator<InstrumentProxyBinder> CREATOR =
            new Creator<InstrumentProxyBinder>() {
                @Override
                public InstrumentProxyBinder createFromParcel(Parcel source) {
                    return new InstrumentProxyBinder(source.readStrongBinder());
                }

                @Override
                public InstrumentProxyBinder[] newArray(int size) {
                    return new InstrumentProxyBinder[size];
                }
            };

    private final IBinder binder;

    InstrumentProxyBinder(IBinder binder) {
        this.binder = binder;
    }

    IBinder binder() {
        return binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeStrongBinder(binder);
    }
}
