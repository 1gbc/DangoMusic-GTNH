"use strict";

const { createPrivateKey, sign } = require("node:crypto");
const { readFileSync } = require("node:fs");

try {
    const [teamId, keyId, keyPath] = process.argv.slice(2);
    if (!/^[A-Z0-9]{10}$/.test(teamId) || !/^[A-Z0-9]{10}$/.test(keyId) || !keyPath) {
        throw new Error("Invalid arguments");
    }
    const key = createPrivateKey(readFileSync(keyPath));
    if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails.namedCurve !== "prime256v1") {
        throw new Error("Expected a P-256 key");
    }
    const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
    const now = Math.floor(Date.now() / 1000);
    const payload = encode({ alg: "ES256", kid: keyId, typ: "JWT" }) + "." +
        encode({ iss: teamId, iat: now, exp: now + 3600 });
    const signature = sign("sha256", Buffer.from(payload), { key, dsaEncoding: "ieee-p1363" });
    process.stdout.write(payload + "." + signature.toString("base64url"));
} catch {
    console.error("Could not sign. Check Team ID, Key ID, and the path to the Media Services .p8 key.");
    process.exitCode = 1;
}
