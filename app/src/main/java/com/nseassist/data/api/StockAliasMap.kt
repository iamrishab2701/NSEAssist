package com.nseassist.data.api

/**
 * Maps NSE stock symbols to their well-known full names and common aliases.
 * Used to improve news search quality — searching both symbol and common name
 * catches more relevant articles than symbol alone.
 */
object StockAliasMap {

    private val aliases: Map<String, List<String>> = mapOf(
        // Banking
        "SBIN" to listOf("State Bank of India", "SBI"),
        "HDFCBANK" to listOf("HDFC Bank"),
        "ICICIBANK" to listOf("ICICI Bank"),
        "AXISBANK" to listOf("Axis Bank"),
        "KOTAKBANK" to listOf("Kotak Mahindra Bank", "Kotak Bank"),
        "BANDHANBNK" to listOf("Bandhan Bank"),
        "FEDERALBNK" to listOf("Federal Bank"),
        "IDFCFIRSTB" to listOf("IDFC First Bank"),
        "INDUSINDBK" to listOf("IndusInd Bank"),
        "RBLBANK" to listOf("RBL Bank"),
        "YESBANK" to listOf("Yes Bank"),
        "PNB" to listOf("Punjab National Bank"),
        "BANKBARODA" to listOf("Bank of Baroda"),
        "CANBK" to listOf("Canara Bank"),
        "UNIONBANK" to listOf("Union Bank of India"),
        "MAHABANK" to listOf("Bank of Maharashtra"),
        "IOB" to listOf("Indian Overseas Bank"),
        "CENTRALBK" to listOf("Central Bank of India"),

        // IT
        "TCS" to listOf("Tata Consultancy Services"),
        "INFY" to listOf("Infosys"),
        "WIPRO" to listOf("Wipro"),
        "HCLTECH" to listOf("HCL Technologies"),
        "TECHM" to listOf("Tech Mahindra"),
        "LTTS" to listOf("LTIMindtree", "L&T Technology Services"),
        "MPHASIS" to listOf("Mphasis"),
        "PERSISTENT" to listOf("Persistent Systems"),
        "COFORGE" to listOf("Coforge"),
        "KPITTECH" to listOf("KPIT Technologies"),

        // Energy
        "RELIANCE" to listOf("Reliance Industries"),
        "ONGC" to listOf("Oil and Natural Gas Corporation", "ONGC"),
        "BPCL" to listOf("Bharat Petroleum", "BPCL"),
        "IOC" to listOf("Indian Oil Corporation", "Indian Oil"),
        "HINDPETRO" to listOf("Hindustan Petroleum", "HPCL"),
        "NTPC" to listOf("NTPC Limited", "NTPC"),
        "POWERGRID" to listOf("Power Grid Corporation", "Power Grid"),
        "ADANIPORTS" to listOf("Adani Ports"),
        "ADANIENT" to listOf("Adani Enterprises"),
        "ADANIGREEN" to listOf("Adani Green Energy"),
        "ADANIPOWER" to listOf("Adani Power"),
        "TATAPOWER" to listOf("Tata Power"),
        "TORNTPOWER" to listOf("Torrent Power"),
        "CESC" to listOf("CESC Limited"),

        // Auto
        "TATAMOTORS" to listOf("Tata Motors"),
        "MARUTI" to listOf("Maruti Suzuki"),
        "M&M" to listOf("Mahindra & Mahindra", "Mahindra"),
        "BAJAJ-AUTO" to listOf("Bajaj Auto"),
        "HEROMOTOCO" to listOf("Hero MotoCorp"),
        "EICHERMOT" to listOf("Eicher Motors", "Royal Enfield"),
        "ASHOKLEY" to listOf("Ashok Leyland"),
        "TVSMOTOR" to listOf("TVS Motor"),
        "BOSCHLTD" to listOf("Bosch India"),
        "MOTHERSON" to listOf("Samvardhana Motherson"),

        // Pharma
        "SUNPHARMA" to listOf("Sun Pharmaceutical", "Sun Pharma"),
        "DRREDDY" to listOf("Dr Reddy's Laboratories", "Dr Reddys"),
        "CIPLA" to listOf("Cipla"),
        "DIVISLAB" to listOf("Divi's Laboratories", "Divis Lab"),
        "AUROPHARMA" to listOf("Aurobindo Pharma"),
        "LUPIN" to listOf("Lupin Limited"),
        "BIOCON" to listOf("Biocon"),
        "GLENMARK" to listOf("Glenmark Pharmaceuticals"),
        "TORNTPHARM" to listOf("Torrent Pharmaceuticals"),
        "ALKEM" to listOf("Alkem Laboratories"),

        // FMCG
        "HINDUNILVR" to listOf("Hindustan Unilever", "HUL"),
        "ITC" to listOf("ITC Limited"),
        "NESTLEIND" to listOf("Nestle India"),
        "BRITANNIA" to listOf("Britannia Industries"),
        "DABUR" to listOf("Dabur India"),
        "GODREJCP" to listOf("Godrej Consumer Products"),
        "MARICO" to listOf("Marico"),
        "EMAMILTD" to listOf("Emami"),
        "COLPAL" to listOf("Colgate-Palmolive India"),
        "VBL" to listOf("Varun Beverages"),

        // Finance / NBFC
        "BAJFINANCE" to listOf("Bajaj Finance"),
        "BAJAJFINSV" to listOf("Bajaj Finserv"),
        "HDFCLIFE" to listOf("HDFC Life Insurance"),
        "SBILIFE" to listOf("SBI Life Insurance"),
        "ICICIGI" to listOf("ICICI Lombard"),
        "LICIHSGFIN" to listOf("LIC Housing Finance"),
        "CHOLAFIN" to listOf("Cholamandalam Finance"),
        "MUTHOOTFIN" to listOf("Muthoot Finance"),
        "MANAPPURAM" to listOf("Manappuram Finance"),
        "PNBHOUSING" to listOf("PNB Housing Finance"),

        // Infrastructure / Metals
        "LT" to listOf("Larsen & Toubro", "L&T"),
        "TATASTEEL" to listOf("Tata Steel"),
        "JSWSTEEL" to listOf("JSW Steel"),
        "HINDALCO" to listOf("Hindalco Industries"),
        "VEDL" to listOf("Vedanta"),
        "COALINDIA" to listOf("Coal India"),
        "NMDC" to listOf("NMDC"),
        "SAIL" to listOf("Steel Authority of India", "SAIL"),
        "NATIONALUM" to listOf("National Aluminium", "NALCO"),
        "HINDZINC" to listOf("Hindustan Zinc"),

        // Telecom
        "BHARTIARTL" to listOf("Bharti Airtel", "Airtel"),
        "IDEA" to listOf("Vodafone Idea", "Vi"),
        "TATACOMM" to listOf("Tata Communications"),

        // Others
        "ASIANPAINT" to listOf("Asian Paints"),
        "PIDILITIND" to listOf("Pidilite Industries"),
        "BERGEPAINT" to listOf("Berger Paints"),
        "HAVELLS" to listOf("Havells India"),
        "VOLTAS" to listOf("Voltas"),
        "WHIRLPOOL" to listOf("Whirlpool India"),
        "TITAN" to listOf("Titan Company"),
        "TRENT" to listOf("Trent"),
        "NAUKRI" to listOf("Info Edge", "Naukri.com"),
        "ZOMATO" to listOf("Zomato"),
        "PAYTM" to listOf("One97 Communications", "Paytm"),
        "NYKAA" to listOf("FSN E-Commerce", "Nykaa"),
        "POLICYBZR" to listOf("PB Fintech", "PolicyBazaar"),
        "DELHIVERY" to listOf("Delhivery"),
        "IRCTC" to listOf("Indian Railway Catering", "IRCTC"),
        "IRCON" to listOf("IRCON International"),
        "RVNL" to listOf("Rail Vikas Nigam"),
        "IRFC" to listOf("Indian Railway Finance Corporation"),
        "HAL" to listOf("Hindustan Aeronautics", "HAL"),
        "BEL" to listOf("Bharat Electronics"),
        "BHEL" to listOf("Bharat Heavy Electricals", "BHEL"),
        "CONCOR" to listOf("Container Corporation of India", "CONCOR"),

        // Additional common stocks
        "MOTHERSON" to listOf("Samvardhana Motherson", "Motherson Sumi"),
        "APOLLOHOSP" to listOf("Apollo Hospitals"),
        "FORTIS" to listOf("Fortis Healthcare"),
        "MAXHEALTH" to listOf("Max Healthcare"),
        "NARAYANAHRD" to listOf("Narayana Hrudayalaya"),
        "MFSL" to listOf("Max Financial Services"),
        "ICICIPRULI" to listOf("ICICI Prudential Life Insurance"),
        "HDFCAMC" to listOf("HDFC Asset Management"),
        "NIPPONLIFE" to listOf("Nippon Life India"),
        "ABCAPITAL" to listOf("Aditya Birla Capital"),
        "ABFRL" to listOf("Aditya Birla Fashion"),
        "OBEROIRLTY" to listOf("Oberoi Realty"),
        "DLF" to listOf("DLF Limited"),
        "GODREJPROP" to listOf("Godrej Properties"),
        "PRESTIGE" to listOf("Prestige Estates"),
        "BRIGADE" to listOf("Brigade Enterprises"),
        "PHOENIXLTD" to listOf("Phoenix Mills"),
        "INDIAMART" to listOf("IndiaMART InterMESH"),
        "JUSTDIAL" to listOf("Just Dial"),
        "CAMPUS" to listOf("Campus Activewear"),
        "MATASNTUR" to listOf("Metastable Materials"),
        "TATAELXSI" to listOf("Tata Elxsi"),
        "LTIM" to listOf("LTIMindtree"),
        "OFSS" to listOf("Oracle Financial Services"),
        "MASTEK" to listOf("Mastek"),
        "HEXAWARE" to listOf("Hexaware Technologies"),
        "CYIENT" to listOf("Cyient"),
        "TANLA" to listOf("Tanla Platforms"),
        "ROUTE" to listOf("Route Mobile"),
        "TATACONSUM" to listOf("Tata Consumer Products"),
        "UNITDSPR" to listOf("United Spirits"),
        "RADICO" to listOf("Radico Khaitan"),
        "UBL" to listOf("United Breweries"),
        "MCDOWELL-N" to listOf("McDowell", "United Spirits"),
        "BANKEX" to listOf("Bank of India"),
        "BANKINDIA" to listOf("Bank of India"),
        "IDBI" to listOf("IDBI Bank"),
        "KARURVYSYA" to listOf("Karur Vysya Bank"),
        "DCBBANK" to listOf("DCB Bank"),
        "EQUITASBNK" to listOf("Equitas Small Finance Bank"),
        "UJJIVANSFB" to listOf("Ujjivan Small Finance Bank"),
        "AUBANK" to listOf("AU Small Finance Bank"),
        "SURYAROSNI" to listOf("Surya Roshni"),
        "FIEMIND" to listOf("Fiem Industries"),
        "POLYCAB" to listOf("Polycab India"),
        "KEI" to listOf("KEI Industries"),
        "APARINDS" to listOf("Apar Industries"),
        "CGCL" to listOf("Capri Global Capital"),
        "CHOLAHLDNG" to listOf("Cholamandalam Investment"),
    )

    fun getAliases(symbol: String): List<String> =
        aliases[symbol.uppercase().trim()] ?: emptyList()

    fun getAllSearchTerms(symbol: String, companyName: String): List<String> {
        val terms = mutableListOf<String>()
        terms += symbol.uppercase().trim()
        companyName.trim().takeIf { it.isNotBlank() }?.let { terms += it }
        terms += getAliases(symbol)
        return terms.distinct().filter { it.isNotBlank() }
    }
}
