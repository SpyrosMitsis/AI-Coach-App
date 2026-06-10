/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Allow importing the shared types package from outside /web.
  transpilePackages: [],
  experimental: {
    externalDir: true,
  },
};

export default nextConfig;
