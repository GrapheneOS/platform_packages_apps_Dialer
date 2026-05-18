package com.android.dialer.integration.connection;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Telecom connection service for Dialer integration tests.
 *
 * <p>Telecom verifies that incoming calls are added by the package that owns the
 * PhoneAccountHandle, so this service lives in a separate helper APK and incoming call requests are
 * sent through {@link DialerIntegrationConnectionReceiver}. Dialer remains the default dialer and
 * receives the resulting real platform Telecom call transitions.
 *
 * <p>The service shape follows {@code
 * cts/tests/tests/telecom/src/android/telecom/cts/MockConnectionService.java}.
 */
public final class MockDialerConnectionService extends ConnectionService {
  static final String EXTRA_ADDRESS_PRESENTATION =
      "com.android.dialer.integration.connection.ADDRESS_PRESENTATION";
  private static final long OUTGOING_CONNECTION_DELAY_MILLIS = 2000;

  private final List<TestConnection> connections = new ArrayList<>();

  @Override
  public Connection onCreateIncomingConnection(
      PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
    TestConnection connection = createConnection(connectionManagerPhoneAccount, request);
    connection.setRinging();
    return connection;
  }

  @Override
  public Connection onCreateOutgoingConnection(
      PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
    TestConnection connection = createConnection(connectionManagerPhoneAccount, request);
    connection.setDialing();
    new Handler(Looper.getMainLooper())
        .postDelayed(
            () -> {
              if (connection.getState() != Connection.STATE_DISCONNECTED) {
                connection.setActive();
              }
            },
            OUTGOING_CONNECTION_DELAY_MILLIS);
    return connection;
  }

  @Override
  public void onConference(Connection connection1, Connection connection2) {
    if (!(connection1 instanceof TestConnection) || !(connection2 instanceof TestConnection)) {
      return;
    }
    if (connection1.getConference() != null || connection2.getConference() != null) {
      return;
    }
    TestConference conference =
        new TestConference(this, (TestConnection) connection1, (TestConnection) connection2);
    addConference(conference);
    conference.setActive();
    updateConferenceableConnections();
  }

  private TestConnection createConnection(
      PhoneAccountHandle phoneAccountHandle, ConnectionRequest request) {
    TestConnection connection =
        new TestConnection(this, request.getAddress(), addressPresentation(request));
    connection.setPhoneAccountHandle(phoneAccountHandle);
    connections.add(connection);
    updateConferenceableConnections();
    return connection;
  }

  private static int addressPresentation(ConnectionRequest request) {
    Bundle extras = request.getExtras();
    return extras == null
        ? TelecomManager.PRESENTATION_ALLOWED
        : extras.getInt(EXTRA_ADDRESS_PRESENTATION, TelecomManager.PRESENTATION_ALLOWED);
  }

  private void removeConnection(TestConnection connection) {
    connections.remove(connection);
    updateConferenceableConnections();
  }

  private void updateConferenceableConnections() {
    for (TestConnection connection : connections) {
      List<Connection> conferenceableConnections = new ArrayList<>();
      for (TestConnection otherConnection : connections) {
        if (connection != otherConnection
            && connection.getConference() == null
            && otherConnection.getConference() == null
            && otherConnection.getState() != Connection.STATE_DISCONNECTED) {
          conferenceableConnections.add(otherConnection);
        }
      }
      connection.setConferenceableConnections(conferenceableConnections);
    }
  }

  private static final class TestConnection extends Connection {
    private final MockDialerConnectionService service;

    TestConnection(MockDialerConnectionService service, Uri address, int presentation) {
      this.service = service;
      setAddress(address, presentation);
      setConnectionCapabilities(
          CAPABILITY_HOLD
              | CAPABILITY_SUPPORT_HOLD
              | CAPABILITY_MERGE_CONFERENCE
              | CAPABILITY_SEPARATE_FROM_CONFERENCE
              | CAPABILITY_DISCONNECT_FROM_CONFERENCE);
    }

    @Override
    public void onAnswer(int videoState) {
      setActive();
    }

    @Override
    public void onAnswer() {
      setActive();
    }

    @Override
    public void onReject() {
      setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
      destroy();
      service.removeConnection(this);
    }

    @Override
    public void onDisconnect() {
      setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
      destroy();
      service.removeConnection(this);
    }

    @Override
    public void onHold() {
      setOnHold();
    }

    @Override
    public void onUnhold() {
      setActive();
    }
  }

  private static final class TestConference extends Conference {
    private final MockDialerConnectionService service;

    TestConference(
        MockDialerConnectionService service,
        TestConnection connection1,
        TestConnection connection2) {
      super(connection1.getPhoneAccountHandle());
      this.service = service;
      setConnectionCapabilities(
          Connection.CAPABILITY_HOLD
              | Connection.CAPABILITY_SUPPORT_HOLD
              | Connection.CAPABILITY_MANAGE_CONFERENCE);
      addConnection(connection1);
      addConnection(connection2);
    }

    @Override
    public void onDisconnect() {
      for (Connection connection : new ArrayList<>(getConnections())) {
        connection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        connection.destroy();
        if (connection instanceof TestConnection) {
          service.removeConnection((TestConnection) connection);
        }
      }
      setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
      destroy();
    }

    @Override
    public void onHold() {
      for (Connection connection : getConnections()) {
        connection.setOnHold();
      }
      setOnHold();
    }

    @Override
    public void onUnhold() {
      for (Connection connection : getConnections()) {
        connection.setActive();
      }
      setActive();
    }

    @Override
    public void onSeparate(Connection connection) {
      removeConnection(connection);
      service.updateConferenceableConnections();
      if (getConnections().size() <= 1) {
        destroy();
      }
    }
  }
}
