Pod::Spec.new do |s|

  s.name             = 'cordova-plugin-tcp-server'
  s.version          = '2.0.0'
  s.summary          = 'Resilient TCP/IP Server Cordova plugin for iOS.'
  s.description      = <<~DESC
    Cordova plugin that turns your iOS app into a TCP/IP server.
    v2 features: automatic recovery watchdog, exponential back-off restart,
    SCNetworkReachability-based network-change detection, port-bind retry,
    and structured JS event objects with display-ready error messages.
  DESC

  s.homepage         = 'https://github.com/MVREDDY-STI/cordova-plugin-tcp-server'
  s.license          = { :type => 'ISC', :file => 'LICENSE' }
  s.author           = { 'venkat' => '' }
  s.source           = {
    :git => 'https://github.com/MVREDDY-STI/cordova-plugin-tcp-server.git',
    :tag => s.version.to_s
  }

  # ── Deployment target ──────────────────────────────────────────────────────
  s.ios.deployment_target = '12.0'

  # ── Source files ───────────────────────────────────────────────────────────
  s.source_files = 'src/ios/**/*.{h,m}'
  s.public_header_files = 'src/ios/**/*.h'

  # ── Frameworks ─────────────────────────────────────────────────────────────
  # Cordova/CDV base
  s.dependency 'Cordova'

  # SystemConfiguration is required for SCNetworkReachability which provides
  # real-time network-change callbacks (Wi-Fi switch, LAN drop, restoration).
  s.frameworks = 'SystemConfiguration'

  # ── Compiler flags ─────────────────────────────────────────────────────────
  # ARC is on by default; silence potential deprecation warnings from Cordova headers
  s.pod_target_xcconfig = {
    'GCC_WARN_INHIBIT_ALL_WARNINGS' => 'NO',
    'CLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS' => 'NO'
  }

end
