"use strict";

/**
 * The demo app's backend. No dependencies; Node 18 or later.
 *
 *   node demo/server.js                                   # fake hosted page, no account needed
 *   npm install secondfactor
 *   SECONDFACTOR_API_KEY=sf_... node demo/server.js       # the real secondfactor.ai
 *
 * It does what your own server does: signs the user in, creates a verification
 * session for their phone number, keeps the session's `sid`, and confirms it,
 * once, with the return token the app sends back.
 *
 * Without an API key it serves a stand-in for the hosted page at /fake-verify,
 * so the whole flow can be tried on an emulator without an account. The code
 * on that page is always 123456. It only imitates the hosted page's contract
 * (a `#vst_` URL in, `sf_session_id` and `sf_return_token` back on the return
 * URL); it is not what the real page looks like.
 *
 * Environment:
 *   PORT                  3000 by default.
 *   RETURN_URL            The App Link the app handles; must match
 *                         demo.returnUrl in gradle.properties. With a real key,
 *                         its origin must be listed under Settings → Hosted
 *                         verification. http://app.localhost:3000/verified by default.
 *   DEMO_PHONE            The number "verified" for every user, in E.164.
 *   SECONDFACTOR_API_KEY  Use the real secondfactor.ai instead of the fake page.
 */

const crypto = require("node:crypto");
const http = require("node:http");

const PORT = Number(process.env.PORT || 3000);
const RETURN_URL = process.env.RETURN_URL || `http://app.localhost:${PORT}/verified`;
const DEMO_PHONE = process.env.DEMO_PHONE || "+9779841000001";
const FAKE_CODE = "123456";

const sf = process.env.SECONDFACTOR_API_KEY
  ? new (require("secondfactor").SecondFactor)({ apiKey: process.env.SECONDFACTOR_API_KEY })
  : null;

// Signed-in users by bearer token, and the fake page's sessions by sid. In
// memory, so a restart signs everyone out.
const users = new Map();
const fakeSessions = new Map();

const routes = {
  "POST /signin": async (req) => {
    const { email, password } = await readJson(req);
    if (!email || !password) return [400, { error: "email and password are required" }];
    // A real server checks the password here. The demo accepts any.
    const token = crypto.randomBytes(24).toString("base64url");
    users.set(token, { email, phone: DEMO_PHONE, sid: null });
    return [200, { token, phone: DEMO_PHONE }];
  },

  "POST /verification/start": async (req) => {
    const user = currentUser(req);
    if (!user) return [401, { error: "not signed in" }];
    let url;
    if (sf) {
      const session = await sf.createSession({ to: user.phone, returnUrl: RETURN_URL, clientReferenceId: user.email });
      user.sid = session.sid;
      url = session.url;
    } else {
      user.sid = `VSN${crypto.randomBytes(8).toString("hex").toUpperCase()}`;
      fakeSessions.set(user.sid, { phone: user.phone, returnToken: null });
      url = `http://localhost:${PORT}/fake-verify?sid=${user.sid}#vst_demo`;
    }
    // The sid stays here, bound to this user. The app only gets the page.
    return [200, { url }];
  },

  "POST /verification/finish": async (req) => {
    const user = currentUser(req);
    if (!user) return [401, { error: "not signed in" }];
    const { returnToken } = await readJson(req);
    // The stored sid, never the sf_session_id from the return URL.
    const storedSid = user.sid;
    user.sid = null;
    if (!storedSid) return [400, { error: "no verification in progress" }];
    if (sf) {
      try {
        const { phone } = await sf.verifySession(storedSid, returnToken);
        return [200, { phone }];
      } catch (error) {
        if (error.name === "SecondFactorError") return [400, { error: error.code }];
        throw error;
      }
    }
    const session = fakeSessions.get(storedSid);
    fakeSessions.delete(storedSid); // Confirmed at most once, like the real API.
    if (!session || !session.returnToken || session.returnToken !== returnToken) {
      return [400, { error: "invalid_return_token" }];
    }
    return [200, { phone: session.phone }];
  },

  "GET /fake-verify": async (req, url) => {
    const session = fakeSessions.get(url.searchParams.get("sid"));
    if (!session) return [404, "This verification has ended."];
    return [200, fakePage(url.searchParams.get("sid"), session.phone)];
  },

  "POST /fake-verify": async (req) => {
    const form = new URLSearchParams(await readBody(req));
    const sid = form.get("sid");
    const session = fakeSessions.get(sid);
    if (!session) return [404, "This verification has ended."];
    const back = new URL(RETURN_URL);
    back.searchParams.set("sf_session_id", sid);
    if (form.get("cancel")) {
      fakeSessions.delete(sid);
    } else if (form.get("code") === FAKE_CODE) {
      session.returnToken = `vsr_${crypto.randomBytes(16).toString("base64url")}`;
      back.searchParams.set("sf_return_token", session.returnToken);
    } else {
      return [200, fakePage(sid, session.phone, "That code is not right. Try 123456.")];
    }
    return [303, null, { Location: back.toString() }];
  },

  // Reached only when the app did not take the return URL, which means the App
  // Link is not set up on this device yet.
  "GET /verified": async () => [
    200,
    page(
      "Open the demo app",
      `<p>The app did not open. Run <code>adb shell pm set-app-links-user-selection --user cur --package ai.secondfactor.demo true app.localhost</code> and try again.</p>`,
    ),
  ],
};

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const route = routes[`${req.method} ${url.pathname}`];
    try {
      const [status, body, headers = {}] = route ? await route(req, url) : [404, { error: "not found" }];
      const isJson = body !== null && typeof body === "object";
      res.writeHead(status, { "Content-Type": isJson ? "application/json" : "text/html; charset=utf-8", ...headers });
      res.end(isJson ? JSON.stringify(body) : (body ?? ""));
    } catch (error) {
      console.error(error);
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: error.code || "server_error" }));
    }
  })
  .listen(PORT, () => console.log(`demo server on http://localhost:${PORT} (${sf ? "secondfactor.ai" : "fake hosted page"})`));

function currentUser(req) {
  const match = /^Bearer (.+)$/.exec(req.headers.authorization || "");
  return match ? users.get(match[1]) : undefined;
}

async function readBody(req) {
  let body = "";
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 10_000) throw new Error("request body too large");
  }
  return body;
}

async function readJson(req) {
  try {
    return JSON.parse(await readBody(req)) || {};
  } catch {
    return {};
  }
}

function fakePage(sid, phone, error = "") {
  return page(
    "Enter your code",
    `<p>We sent a 6-digit code to the number ending in ${escape(phone.slice(-4))}.<br>
       <small>Demo: the code is always ${FAKE_CODE}.</small></p>
     <form method="post">
       <input type="hidden" name="sid" value="${escape(sid)}">
       <input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" autofocus>
       ${error ? `<p class="error">${escape(error)}</p>` : ""}
       <button>Verify</button>
       <button name="cancel" value="1" class="link">Not your number?</button>
     </form>`,
  );
}

function page(title, content) {
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title>
<style>
  body { font: 16px system-ui, sans-serif; max-width: 360px; margin: 64px auto; padding: 0 24px; color: #1b1b1f; }
  h1 { font-size: 24px; } p { color: #46464f; line-height: 1.5; }
  input[name=code] { width: 100%; box-sizing: border-box; font-size: 28px; letter-spacing: 8px; text-align: center; padding: 12px; border: 1px solid #c7c5d0; border-radius: 8px; }
  button { width: 100%; padding: 14px; margin-top: 16px; font-size: 16px; border: 0; border-radius: 24px; background: #4b57a9; color: #fff; }
  button.link { background: none; color: #4b57a9; margin-top: 4px; }
  .error { color: #ba1a1a; } .badge { font-size: 12px; color: #77767f; }
</style>
<p class="badge">secondfactor.ai · local demo page</p>
<h1>${escape(title)}</h1>${content}`;
}

function escape(text) {
  return String(text).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
