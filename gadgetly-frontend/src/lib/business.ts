// Only verified or clearly demo-safe details belong here. Contact, hours and
// tax identifiers must be supplied by the shop before they appear publicly.
interface BusinessDetails {
  name: string
  altName: string
  tagline: string
  location: string
  phone?: string
  email?: string
  timings?: string
  gstin?: string
  brands: string[]
}

export const BUSINESS: BusinessDetails = {
  name: 'Gadgetly',
  altName: 'Gadgetly Electronics',
  tagline: 'Your Trusted Electronics & Appliance Destination',
  location: 'Rampura Phul, Punjab',
  brands: ['LG', 'Samsung', 'Sony', 'Whirlpool', 'IFB', 'Voltas', 'Mi', 'Haier', 'Prestige', 'Philips', 'Bajaj', 'JBL', 'Symphony'],
}
