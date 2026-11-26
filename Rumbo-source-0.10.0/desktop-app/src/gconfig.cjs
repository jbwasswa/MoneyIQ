'use strict';

// Desktop OAuth credentials must be supplied through the runtime environment,
// never committed to the public repository.
module.exports = {
  desktopClientId: process.env.RUMBO_DESKTOP_CLIENT_ID || '',
  desktopClientSecret: process.env.RUMBO_DESKTOP_CLIENT_SECRET || '',
  webClientId: process.env.RUMBO_WEB_CLIENT_ID || '',
};
