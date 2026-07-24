<template>
  <div class="auth-layout">
    <div class="auth-brand">
      <div class="brand-bg">
        <div class="particle particle-1"></div>
        <div class="particle particle-2"></div>
        <div class="particle particle-3"></div>
        <div class="particle particle-4"></div>
        <div class="particle particle-5"></div>
        <div class="particle particle-6"></div>
        <div class="particle particle-7"></div>
        <div class="particle particle-8"></div>
      </div>
      <div class="brand-content">
        <div class="brand-logo">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5"/>
            <path d="M12 2 L12 22 M2 12 L22 12" stroke="currentColor" stroke-width="1" opacity="0.4"/>
            <circle cx="12" cy="12" r="4" fill="currentColor" opacity="0.25"/>
            <circle cx="12" cy="12" r="1.5" fill="currentColor"/>
          </svg>
        </div>
        <h1 class="brand-title">{{ $t('brand.title') }}</h1>
        <p class="brand-desc">{{ $t('brand.desc') }}</p>
        <div class="brand-features">
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>{{ $t('brand.feature1') }}</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>{{ $t('brand.feature2') }}</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>{{ $t('brand.feature3') }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="auth-form-wrapper">
      <div class="auth-form-card">
        <slot />
      </div>
      <div class="auth-footer">
        <span>{{ $t('brand.copyright') }}</span>
        <a href="https://github.com/zferrys/codeatlas" target="_blank" class="auth-footer-link">GitHub</a>
      </div>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.auth-layout {
  display: flex;
  min-height: 100vh;
  overflow: hidden;
}

.auth-brand {
  flex: 0 0 480px;
  background: linear-gradient(135deg, #0a1628, #0d2137, #0f2b45);
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.brand-bg {
  position: absolute;
  inset: 0;
}

.particle {
  position: absolute;
  border-radius: 50%;
  background: rgba(22, 119, 255, 0.3);
  animation: float 6s ease-in-out infinite;
}
.particle-1 { width: 80px; height: 80px; top: 10%; left: 10%; animation-delay: 0s; }
.particle-2 { width: 40px; height: 40px; top: 20%; right: 15%; animation-delay: 1s; background: rgba(0, 80, 179, 0.3); }
.particle-3 { width: 60px; height: 60px; bottom: 15%; left: 20%; animation-delay: 2s; background: rgba(22, 119, 255, 0.2); }
.particle-4 { width: 30px; height: 30px; top: 60%; right: 25%; animation-delay: 0.5s; }
.particle-5 { width: 50px; height: 50px; top: 40%; left: 30%; animation-delay: 1.5s; background: rgba(0, 80, 179, 0.25); }
.particle-6 { width: 20px; height: 20px; bottom: 30%; right: 40%; animation-delay: 2.5s; }
.particle-7 { width: 70px; height: 70px; top: 70%; left: 50%; animation-delay: 3s; background: rgba(22, 119, 255, 0.15); }
.particle-8 { width: 35px; height: 35px; top: 15%; left: 60%; animation-delay: 3.5s; background: rgba(0, 80, 179, 0.3); }

@keyframes float {
  0%, 100% { transform: translateY(0) scale(1); opacity: 0.6; }
  50% { transform: translateY(-20px) scale(1.1); opacity: 1; }
}

.brand-content {
  position: relative;
  z-index: 1;
  text-align: center;
  color: #fff;
  padding: 40px;
}

.brand-logo {
  color: #1677ff;
  margin-bottom: 24px;
  display: flex;
  justify-content: center;
}

.brand-title {
  font-size: 36px;
  font-weight: 800;
  letter-spacing: 2px;
  margin: 0 0 12px;
  background: linear-gradient(135deg, #1677ff, #69b1ff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.brand-desc {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.65);
  margin: 0 0 40px;
  line-height: 1.6;
}

.brand-features {
  text-align: left;
  display: inline-flex;
  flex-direction: column;
  gap: 16px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.8);
}

.feature-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1677ff;
  flex-shrink: 0;
}

.auth-form-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #f7f8fc;
  padding: 40px;
}

.auth-form-card {
  width: 100%;
  max-width: 420px;
}

.auth-footer {
  position: absolute;
  bottom: 20px;
  right: 40px;
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  color: #999;
}

.auth-footer-link {
  color: #1677ff;
  text-decoration: none;
}

@media (max-width: 900px) {
  .auth-brand {
    display: none;
  }
  .auth-form-wrapper {
    padding: 24px;
  }
}
</style>
