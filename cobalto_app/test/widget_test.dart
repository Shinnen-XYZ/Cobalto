import 'dart:convert';

import 'package:cobalto_app/main.dart';
import 'package:material_ui/material_ui.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('email confirmation sends token in POST body and resend uses authenticated session', () async {
    final requests = <http.Request>[];
    final tokenStorage = MemoryAuthTokenStorage({
      'cobalto_access_token': 'access-token',
      'cobalto_refresh_token': 'refresh-token',
    });
    final client = MockClient((request) async {
      requests.add(request);
      if (request.url.path == '/api/me') {
        return http.Response(
          jsonEncode({
            'id': 'user-1',
            'fullName': 'Sandbox User',
            'email': 'person@example.test',
            'kycStatus': 'NOT_VERIFIED',
            'emailVerified': false,
            'mfaEnabled': false,
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      if (request.url.path == '/api/auth/email-verification/confirm') {
        expect(request.method, 'POST');
        expect(jsonDecode(request.body), {
          'token': 'opaque-verification-token',
        });
        return http.Response('{}', 200);
      }
      expect(request.url.path, '/api/auth/email-verification/resend');
      expect(request.method, 'POST');
      expect(request.headers['authorization'], 'Bearer access-token');
      return http.Response(
        jsonEncode({
          'emailVerified': false,
          'message':
              'Si tu cuenta requiere verificación, enviaremos un correo.',
        }),
        202,
        headers: {'content-type': 'application/json'},
      );
    });
    final api = ApiClient(httpClient: client, tokenStorage: tokenStorage);

    final user = await api.restoreSession();
    await api.confirmEmailVerification('opaque-verification-token');
    expect(await api.resendEmailVerification(), isFalse);
    expect(user?.emailVerified, isFalse);
    expect(requests, hasLength(3));
    client.close();
  });

  test(
    'recipient confirmation and transfer use the protected REST contract',
    () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        if (request.url.path == '/api/recipients/confirm') {
          expect(
            request.url.queryParameters['email'],
            'recipient+test@example.test',
          );
          return http.Response(
            jsonEncode({
              'fullName': 'Sandbox Recipient',
              'maskedEmail': 'r***@example.test',
            }),
            200,
          );
        }
        expect(request.url.path, '/api/transfers');
        expect(request.headers['idempotency-key'], 'transfer-key');
        expect(jsonDecode(request.body), {
          'recipientEmail': 'recipient+test@example.test',
          'amount': 12.35,
          'password': 'step-up-password',
          'mfaFactor': '123456',
        });
        return http.Response(
          jsonEncode(_sampleReceipt()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });
      final api = ApiClient(httpClient: client);

      final recipient = await api.confirmRecipient(
        'recipient+test@example.test',
      );
      final receipt = await api.transfer(
        'recipient+test@example.test',
        '12.35',
        'step-up-password',
        '123456',
        'transfer-key',
      );

      expect(recipient.fullName, 'Sandbox Recipient');
      expect(receipt.status, 'COMPLETED');
      expect(receipt.receiptNumber, 'CB-transaction-1');
      expect(requests, hasLength(2));
      client.close();
    },
  );

  test(
    'expired access token is refreshed and the rotated token is persisted',
    () async {
      final tokenStorage = MemoryAuthTokenStorage({
        'cobalto_access_token': 'expired-access',
        'cobalto_refresh_token': 'refresh-before-rotation',
      });
      var profileRequests = 0;
      var refreshRequests = 0;
      final client = MockClient((request) async {
        if (request.url.path == '/api/me') {
          profileRequests++;
          if (request.headers['authorization'] == 'Bearer expired-access') {
            return http.Response('', 401);
          }
          expect(request.headers['authorization'], 'Bearer fresh-access');
          return http.Response(
            jsonEncode({
              'id': 'user-1',
              'fullName': 'Sandbox User',
              'email': 'person@example.com',
              'kycStatus': 'NOT_VERIFIED',
              'mfaEnabled': false,
            }),
            200,
            headers: {'content-type': 'application/json'},
          );
        }

        expect(request.url.path, '/api/auth/refresh');
        refreshRequests++;
        expect(jsonDecode(request.body), {
          'refreshToken': 'refresh-before-rotation',
        });
        return http.Response(
          jsonEncode({
            'accessToken': 'fresh-access',
            'refreshToken': 'refresh-after-rotation',
            'expiresInSeconds': 900,
            'user': {
              'id': 'user-1',
              'fullName': 'Sandbox User',
              'email': 'person@example.com',
              'kycStatus': 'NOT_VERIFIED',
              'mfaEnabled': false,
            },
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      final api = ApiClient(httpClient: client, tokenStorage: tokenStorage);
      final user = await api.restoreSession();

      expect(user?.email, 'person@example.com');
      expect(profileRequests, 2);
      expect(refreshRequests, 1);
      expect(tokenStorage.values['cobalto_access_token'], 'fresh-access');
      expect(
        tokenStorage.values['cobalto_refresh_token'],
        'refresh-after-rotation',
      );
      client.close();
    },
  );

  testWidgets(
    'email verification link confirms token and shows completion state',
    (tester) async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/auth/email-verification/confirm');
        expect(request.method, 'POST');
        expect(jsonDecode(request.body), {'token': 'opaque-token'});
        return http.Response(
          jsonEncode({
            'emailVerified': true,
            'message': 'El correo fue verificado.',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      await tester.pumpWidget(
        MaterialApp(
          home: EmailVerificationScreen(
            api: ApiClient(httpClient: client),
            token: 'opaque-token',
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Correo verificado'), findsOneWidget);
      expect(
        find.text('Tu dirección ya puede recibir transferencias Sandbox.'),
        findsOneWidget,
      );
      client.close();
    },
  );

  testWidgets('unverified user sees resend action and retry states', (
    tester,
  ) async {
    var resendRequests = 0;
    final client = MockClient((request) async {
      if (request.url.path == '/api/wallet') {
        return http.Response(
          jsonEncode({
            'currency': 'GTQ',
            'balance': 100,
            'availableBalance': 100,
            'heldBalance': 0,
            'movements': [],
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      }
      expect(request.url.path, '/api/auth/email-verification/resend');
      resendRequests++;
      return http.Response(
        jsonEncode({
          'emailVerified': false,
          'message':
              'Si tu cuenta requiere verificación, enviaremos un correo.',
        }),
        202,
        headers: {'content-type': 'application/json'},
      );
    });
    final user = AuthUser(
      id: 'user-1',
      fullName: 'Sandbox User',
      email: 'person@example.test',
      kycStatus: 'NOT_VERIFIED',
      emailVerified: false,
      mfaEnabled: false,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(
          api: ApiClient(httpClient: client),
          user: user,
          onUserUpdated: (_) {},
          onSignOut: () async {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.text('Verifica tu correo para recibir transferencias.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Reenviar correo'));
    await tester.pumpAndSettle();

    expect(resendRequests, 1);
    expect(
      find.text('Si tu cuenta requiere verificación, enviaremos un correo.'),
      findsOneWidget,
    );
    client.close();
  });

  testWidgets(
    'login form shows Cobalto identity and validates required input',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: AuthScreen(api: ApiClient(), onAuthenticated: (_) {}),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('cobalto'), findsOneWidget);
      expect(find.text('Iniciar sesión'), findsOneWidget);

      await tester.tap(find.text('Iniciar sesión'));
      await tester.pumpAndSettle();

      expect(find.text('Ingresa un correo válido'), findsOneWidget);
      expect(find.text('Usa al menos 12 caracteres'), findsOneWidget);
    },
  );

  testWidgets(
    'MFA-enabled login requires a second factor before authentication',
    (tester) async {
      final client = MockClient((request) async {
        expect(request.url.path, '/api/auth/login');
        return http.Response(
          jsonEncode({
            'mfaRequired': true,
            'mfaChallenge': 'one-time-challenge',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      });
      var authenticated = false;
      final api = ApiClient(httpClient: client);

      await tester.pumpWidget(
        MaterialApp(
          home: AuthScreen(
            api: api,
            onAuthenticated: (_) => authenticated = true,
          ),
        ),
      );
      await tester.enterText(
        find.byType(TextFormField).at(0),
        'person@example.com',
      );
      await tester.enterText(
        find.byType(TextFormField).at(1),
        'correct-horse-battery',
      );
      await tester.tap(find.text('Iniciar sesión'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Verificación en dos pasos'), findsOneWidget);
      expect(authenticated, isFalse);
      await tester.tap(find.text('Cancelar'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(authenticated, isFalse);
      client.close();
    },
  );

  testWidgets(
    'transfer dialog confirms recipient and reauthenticates before posting',
    (tester) async {
      var posted = false;
      final client = MockClient((request) async {
        if (request.url.path == '/api/recipients/confirm') {
          return http.Response(
            jsonEncode({
              'fullName': 'Sandbox Recipient',
              'maskedEmail': 'r***@example.test',
            }),
            200,
            headers: {'content-type': 'application/json'},
          );
        }
        expect(request.url.path, '/api/transfers');
        expect(jsonDecode(request.body)['password'], 'step-up-password');
        expect(request.headers['idempotency-key'], isNotEmpty);
        posted = true;
        return http.Response(
          jsonEncode(_sampleReceipt()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });
      TransferReceipt? result;
      final api = ApiClient(httpClient: client);

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: FilledButton(
                onPressed: () async {
                  result = await showDialog<TransferReceipt>(
                    context: context,
                    builder: (_) => TransferDialog(api: api, mfaEnabled: false),
                  );
                },
                child: const Text('Open transfer'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open transfer'));
      await tester.pumpAndSettle();
      expect(find.text('Enviar'), findsOneWidget);
      expect(posted, isFalse);

      await tester.enterText(
        find.byType(TextFormField).at(0),
        'recipient@example.test',
      );
      await tester.tap(find.text('Confirmar destinatario'));
      await tester.pumpAndSettle();
      expect(find.text('Sandbox Recipient'), findsOneWidget);

      await tester.enterText(find.byType(TextFormField).at(1), '5.25');
      await tester.enterText(
        find.byType(TextFormField).at(2),
        'step-up-password',
      );
      await tester.tap(find.text('Enviar'));
      await tester.pumpAndSettle();

      expect(posted, isTrue);
      expect(result?.amount, 12.35);
      client.close();
    },
  );

  testWidgets(
    'transfer dialog does not select unverified recipients or post transfers',
    (tester) async {
      var posted = false;
      final client = MockClient((request) async {
        if (request.url.path == '/api/recipients/confirm') {
          return http.Response(
            jsonEncode({
              'status': 404,
              'code': 'RECIPIENT_NOT_FOUND',
              'message': 'No se encontró una cuenta destinataria verificada',
            }),
            404,
            headers: {'content-type': 'application/json'},
          );
        }
        posted = true;
        return http.Response(
          jsonEncode(_sampleReceipt()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });
      final api = ApiClient(httpClient: client);

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: FilledButton(
                onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => TransferDialog(api: api, mfaEnabled: false),
                ),
                child: const Text('Open transfer'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open transfer'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextFormField).at(0),
        'recipient@example.test',
      );
      await tester.tap(find.text('Confirmar destinatario'));
      await tester.pumpAndSettle();

      expect(
        find.text('No se encontró una cuenta destinataria verificada'),
        findsOneWidget,
      );
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'Enviar'))
            .onPressed,
        isNull,
      );
      expect(posted, isFalse);
      client.close();
    },
  );

  testWidgets(
    'authorized user confirms reason and password before reversing a transfer',
    (tester) async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        expect(request.method, 'POST');
        expect(request.url.path, '/api/transfers/transaction-1/reversal');
        expect(request.headers['idempotency-key'], isNotEmpty);
        expect(jsonDecode(request.body), {
          'reason': 'Duplicada',
          'password': 'step-up-password',
        });
        return http.Response(
          jsonEncode(_sampleReversalReceipt()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });
      final api = ApiClient(httpClient: client);
      TransferReceipt? result;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: FilledButton(
                onPressed: () async {
                  result = await showModalBottomSheet<TransferReceipt>(
                    context: context,
                    builder: (_) => TransferReceiptSheet(
                      receipt: TransferReceipt.fromJson(_sampleReceipt()),
                      api: api,
                      canReverseTransfers: true,
                      mfaEnabled: false,
                    ),
                  );
                },
                child: const Text('Open receipt'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open receipt'));
      await tester.pumpAndSettle();
      expect(find.text('Revertir transferencia'), findsOneWidget);

      await tester.ensureVisible(find.text('Revertir transferencia'));
      await tester.tap(find.text('Revertir transferencia'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField).at(0), 'Duplicada');
      await tester.enterText(
        find.byType(TextFormField).at(1),
        'step-up-password',
      );
      await tester.tap(
        find.widgetWithText(FilledButton, 'Confirmar reversión'),
      );
      await tester.pumpAndSettle();

      expect(requests, hasLength(1));
      expect(result?.operationType, 'REVERSAL');
      expect(result?.originalTransactionId, 'transaction-1');
      expect(result?.reason, 'Duplicada');
      client.close();
    },
  );
}

Map<String, Object> _sampleReceipt() => {
  'transactionId': 'transaction-1',
  'receiptNumber': 'CB-transaction-1',
  'status': 'COMPLETED',
  'direction': 'SENT',
  'currency': 'GTQ',
  'amount': 12.35,
  'createdAt': '2026-11-21T18:00:00Z',
  'description': 'Transferencia entre usuarios',
  'counterpartyName': 'Sandbox Recipient',
  'maskedCounterpartyEmail': 'r***@example.test',
};

Map<String, Object> _sampleReversalReceipt() => {
  'transactionId': 'reversal-1',
  'receiptNumber': 'CB-RV-reversal-1',
  'status': 'COMPLETED',
  'direction': 'AUTHORIZED',
  'currency': 'GTQ',
  'amount': 12.35,
  'createdAt': '2026-11-21T18:00:00Z',
  'description': 'Reversión de transferencia transaction-1',
  'counterpartyName': 'Operación autorizada',
  'maskedCounterpartyEmail': '***',
  'operationType': 'REVERSAL',
  'originalTransactionId': 'transaction-1',
  'reason': 'Duplicada',
};

class MemoryAuthTokenStorage implements AuthTokenStorage {
  MemoryAuthTokenStorage([Map<String, String>? initialValues])
    : values = Map.of(initialValues ?? {});

  final Map<String, String> values;

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}
