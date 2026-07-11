# 우리 캘린더 — Codemagic CI에서 위젯 익스텐션 타깃 자동 주입
# Codemagic이 매 빌드마다 네이티브 프로젝트를 재생성하므로, `npx cap add ios`(또는 sync) 직후
# 이 스크립트를 실행해 WidgetKit 익스텐션 타깃을 Xcode 프로젝트에 추가한다.
#
# 사용 (Codemagic 워크플로, cap sync 후 / xcodebuild 전):
#   gem install xcodeproj --no-document
#   ruby widget/ios/add_widget_target.rb ios/App/App.xcodeproj
#
# 전제 (Apple Developer에서 1회 생성 — README 체크리스트 참고):
#   - App Group:      group.com.lsung.uricalendar   (앱 + 위젯 둘 다)
#   - 위젯 번들 ID:    com.lsung.uricalendar.widget  (프로비저닝 프로파일 포함)

require 'xcodeproj'

proj_path = ARGV[0] || 'ios/App/App.xcodeproj'
TEAM_ID = ARGV[1] || 'ZZ9B9BWM8T'                     # 워크플로의 DEVELOPMENT_TEAM과 동일
WIDGET_NAME = 'UriCalendarWidget'
WIDGET_BUNDLE_ID = 'com.lsung.uricalendar.widget'
APP_GROUP = 'group.com.lsung.uricalendar'
SRC_DIR = File.expand_path(File.dirname(__FILE__))   # widget/ios

project = Xcodeproj::Project.open(proj_path)
app_target = project.targets.find { |t| t.name == 'App' }
abort('App 타깃을 찾을 수 없음') unless app_target

if project.targets.any? { |t| t.name == WIDGET_NAME }
  puts '위젯 타깃이 이미 있음 — 스킵'
  exit 0
end

deployment_target = '17.0'   # AppIntentConfiguration·인터랙티브 위젯이 iOS17+ 필수 (호스트 앱은 16 유지, iOS16 사용자는 위젯만 미노출)

# 1) 위젯 타깃 생성
widget = project.new_target(:app_extension, WIDGET_NAME, :ios, deployment_target)

# 2) 소스/리소스 파일 연결 (widget/ios/*.swift, Info.plist를 ios/App/UriCalendarWidget/로 복사해 참조)
dest_dir = File.join(File.dirname(File.dirname(proj_path)), 'App', WIDGET_NAME)
require 'fileutils'
FileUtils.mkdir_p(dest_dir)
FileUtils.cp(File.join(SRC_DIR, 'UriCalendarWidget.swift'), dest_dir)
FileUtils.cp(File.join(SRC_DIR, 'Info.plist'), dest_dir)

group = project.main_group.new_group(WIDGET_NAME, dest_dir)
swift_ref = group.new_file(File.join(dest_dir, 'UriCalendarWidget.swift'))
widget.add_file_references([swift_ref])

# 3) 빌드 설정
widget.build_configurations.each do |config|
  bs = config.build_settings
  bs['PRODUCT_BUNDLE_IDENTIFIER'] = WIDGET_BUNDLE_ID
  # PRODUCT_NAME이 비면 산출물이 '.appex'(이름 없음)가 되어
  # "Multiple commands produce .appex" 링크 충돌이 남 → 반드시 명시.
  bs['PRODUCT_NAME'] = '$(TARGET_NAME)'
  bs['INFOPLIST_FILE'] = File.join('App', WIDGET_NAME, 'Info.plist')
  bs['SWIFT_VERSION'] = '5.0'
  bs['IPHONEOS_DEPLOYMENT_TARGET'] = deployment_target
  bs['TARGETED_DEVICE_FAMILY'] = '1'                  # 앱이 iPhone 전용이므로 위젯도 동일
  bs['DEVELOPMENT_TEAM'] = TEAM_ID
  bs['MARKETING_VERSION'] = app_target.build_configurations.first.build_settings['MARKETING_VERSION'] || '1.0'
  bs['CURRENT_PROJECT_VERSION'] = app_target.build_configurations.first.build_settings['CURRENT_PROJECT_VERSION'] || '1'
  bs['CODE_SIGN_ENTITLEMENTS'] = File.join('App', WIDGET_NAME, "#{WIDGET_NAME}.entitlements")
  bs['SKIP_INSTALL'] = 'YES'
end

# 4) App Group entitlements (위젯 + 앱 본체 둘 다)
widget_ent = File.join(dest_dir, "#{WIDGET_NAME}.entitlements")
File.write(widget_ent, <<~XML)
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
    <key>com.apple.security.application-groups</key>
    <array><string>#{APP_GROUP}</string></array>
  </dict>
  </plist>
XML
group.new_file(widget_ent)

# 앱 본체 entitlements에 App Group 추가 (기존 파일 있으면 병합, 없으면 생성)
app_ent_path = nil
app_target.build_configurations.each do |config|
  app_ent_path ||= config.build_settings['CODE_SIGN_ENTITLEMENTS']
end
if app_ent_path.nil? || app_ent_path.empty?
  app_ent_path = 'App/App.entitlements'
  app_target.build_configurations.each { |c| c.build_settings['CODE_SIGN_ENTITLEMENTS'] = app_ent_path }
end
abs_app_ent = File.join(File.dirname(proj_path), app_ent_path)
FileUtils.mkdir_p(File.dirname(abs_app_ent))
if File.exist?(abs_app_ent)
  content = File.read(abs_app_ent)
  unless content.include?(APP_GROUP)
    content.sub!('<dict>', "<dict>\n  <key>com.apple.security.application-groups</key>\n  <array><string>#{APP_GROUP}</string></array>")
    File.write(abs_app_ent, content)
  end
else
  File.write(abs_app_ent, <<~XML)
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
      <key>com.apple.security.application-groups</key>
      <array><string>#{APP_GROUP}</string></array>
    </dict>
    </plist>
  XML
end

# 5) 앱이 위젯을 임베드하도록 의존성 + Embed App Extensions 페이즈
app_target.add_dependency(widget)
embed = app_target.copy_files_build_phases.find { |p| p.symbol_dst_subfolder_spec == :plug_ins } ||
        app_target.new_copy_files_build_phase('Embed App Extensions')
embed.symbol_dst_subfolder_spec = :plug_ins
product_ref = widget.product_reference
bf = embed.add_file_reference(product_ref)
bf.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }

project.save
puts "✅ #{WIDGET_NAME} 타깃 추가 완료 (#{WIDGET_BUNDLE_ID}, #{APP_GROUP})"
