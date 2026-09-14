/** UC12: ATM & Branch Finder — Redis Geospatial */
(function () {
    'use strict';
    window.WORKSHOP_UC = 'UC12';

    // --- State ---
    var currentApproach = 'native';
    var currentLat = 40.4168;
    var currentLng = -3.7038;
    var map, userMarker, radiusCircle;
    var lastBounds = null;
    var resultMarkers = [];

    // --- DOM refs ---
    var radiusSlider = document.getElementById('radiusSlider');
    var radiusValue = document.getElementById('radiusValue');
    var btnSearch = document.getElementById('btnSearch');
    var commandBox = document.getElementById('commandBox');
    var commandText = document.getElementById('commandText');
    var resultsCount = document.getElementById('resultsCount');
    var latencyBadge = document.getElementById('latencyBadge');
    var resultsList = document.getElementById('resultsList');
    var rqeFilters = document.getElementById('rqeFilters');
    var filterType = document.getElementById('filterType');
    var filterService = document.getElementById('filterService');

    // --- Code tabs ---
    window.initCodeTabs();

    // --- Approach tabs ---
    document.querySelectorAll('.geo-tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            document.querySelectorAll('.geo-tab').forEach(function (t) { t.classList.remove('active'); });
            tab.classList.add('active');
            currentApproach = tab.getAttribute('data-approach');
            rqeFilters.style.display = currentApproach === 'rqe' ? 'flex' : 'none';
        });
    });

    // --- Preset locations ---
    document.querySelectorAll('.geo-preset').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.geo-preset').forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
            currentLat = parseFloat(btn.getAttribute('data-lat'));
            currentLng = parseFloat(btn.getAttribute('data-lng'));
            setUserLocation(currentLat, currentLng);
        });
    });

    // --- Quick scenario buttons ---
    document.querySelectorAll('.geo-scenario').forEach(function (btn) {
        btn.addEventListener('click', function () {
            currentLat = parseFloat(btn.getAttribute('data-lat'));
            currentLng = parseFloat(btn.getAttribute('data-lng'));
            radiusSlider.value = btn.getAttribute('data-radius') || '2';
            radiusValue.textContent = radiusSlider.value;

            // Switch approach tab if specified
            var approach = btn.getAttribute('data-approach');
            if (approach) {
                document.querySelectorAll('.geo-tab').forEach(function (t) { t.classList.remove('active'); });
                var targetTab = document.querySelector('.geo-tab[data-approach="' + approach + '"]');
                if (targetTab) targetTab.classList.add('active');
                currentApproach = approach;
                rqeFilters.style.display = approach === 'rqe' ? 'flex' : 'none';
            }

            // Set RQE filters if specified
            var type = btn.getAttribute('data-type');
            if (type && filterType) filterType.value = type;

            // Clear active preset
            document.querySelectorAll('.geo-preset').forEach(function (b) { b.classList.remove('active'); });

            setUserLocation(currentLat, currentLng);
            doSearch();
        });
    });

    // --- Radius slider ---
    radiusSlider.addEventListener('input', function () {
        radiusValue.textContent = radiusSlider.value;
        updateRadiusCircle();
    });

    // --- Initialize map ---
    map = L.map('map').setView([currentLat, currentLng], 14);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 18
    }).addTo(map);

    // Leaflet caches the container size, so the map keeps the pre-fullscreen width
    // (tiles and markers stay on the left half). Recompute it on every layout switch.
    function refreshMapSize() {
        if (!map) return;
        map.invalidateSize();
        // The previous fitBounds was computed for the old width: refit so the
        // result set fills the new viewport instead of hugging one corner.
        if (lastBounds && lastBounds.length > 0) {
            map.fitBounds(lastBounds, { padding: [40, 40] });
        }
    }

    window.addEventListener('presenter-mode-change', function () {
        refreshMapSize();
        // Run again once the fullscreen layout has settled (CSS height swap).
        setTimeout(refreshMapSize, 300);
    });

    // Custom icons
    var atmIcon = L.divIcon({
        className: 'geo-marker-atm',
        html: '<span style="font-size:11px; font-weight:700; background:#FF4438; color:#fff; padding:3px 5px; border-radius:4px; line-height:1;">ATM</span>',
        iconSize: [28, 28], iconAnchor: [14, 14]
    });
    var branchIcon = L.divIcon({
        className: 'geo-marker-branch',
        html: '<span style="font-size:11px; font-weight:700; background:#091A23; color:#fff; padding:3px 5px; border-radius:4px; line-height:1;">BR</span>',
        iconSize: [28, 28], iconAnchor: [14, 14]
    });
    var userIcon = L.divIcon({
        className: 'geo-marker-user',
        html: '<span style="font-size:14px; font-weight:700; background:#DC382C; color:#fff; padding:2px 6px; border-radius:50%; line-height:1;">You</span>',
        iconSize: [28, 28], iconAnchor: [14, 28]
    });

    // Set initial user marker
    setUserLocation(currentLat, currentLng);

    // Click on map to set location
    map.on('click', function (e) {
        document.querySelectorAll('.geo-preset').forEach(function (b) { b.classList.remove('active'); });
        currentLat = e.latlng.lat;
        currentLng = e.latlng.lng;
        setUserLocation(currentLat, currentLng);
    });

    function setUserLocation(lat, lng) {
        if (userMarker) map.removeLayer(userMarker);
        userMarker = L.marker([lat, lng], { icon: userIcon }).addTo(map);
        userMarker.bindPopup('<strong>Your location</strong>').openPopup();
        map.setView([lat, lng], 14);
        updateRadiusCircle();
    }

    function updateRadiusCircle() {
        var radius = parseFloat(radiusSlider.value) * 1000;
        if (radiusCircle) map.removeLayer(radiusCircle);
        radiusCircle = L.circle([currentLat, currentLng], {
            radius: radius,
            color: 'var(--redis-ink, #091A23)',
            fillColor: '#DC382C',
            fillOpacity: 0.08,
            weight: 1.5,
            dashArray: '6,4'
        }).addTo(map);
    }

    // --- Search ---
    btnSearch.addEventListener('click', doSearch);

    function doSearch() {
        var radius = parseFloat(radiusSlider.value);
        var url;
        if (currentApproach === 'native') {
            url = '/api/geo/search/native?lng=' + currentLng + '&lat=' + currentLat + '&radius=' + radius;
        } else {
            url = '/api/geo/search/rqe?lng=' + currentLng + '&lat=' + currentLat + '&radius=' + radius;
            if (filterType.value !== 'all') url += '&type=' + filterType.value;
            if (filterService.value !== 'all') url += '&service=' + filterService.value;
        }

        btnSearch.disabled = true;
        btnSearch.textContent = 'Searching...';

        fetch(url)
            .then(function (response) {
                window.showLatencyBadge('btnSearch', window.extractLatency(response));
                return window.parseJsonResponse(response);
            })
            .then(function (data) {
                renderResults(data);
                window.showToast(
                    (data.results || []).length
                        ? ('Found ' + (data.results || []).length + ' location(s) within ' + radius + ' km.')
                        : 'No locations matched the current geo filters.',
                    (data.results || []).length ? 'success' : 'warning'
                );
            })
            .catch(function (err) {
                window.showToast((err && err.message) || 'Geo search failed.', 'error');
            })
            .finally(function () {
                btnSearch.disabled = false;
                btnSearch.textContent = 'Search';
            });
    }

    function renderResults(data) {
        // Clear previous markers
        resultMarkers.forEach(function (m) { map.removeLayer(m); });
        resultMarkers = [];

        // Show command
        commandBox.style.display = 'block';
        commandText.textContent = data.command || '';
        window.animateResult(commandBox, 'fade-in');

        // Show latency
        latencyBadge.style.display = 'inline';
        latencyBadge.textContent = data.latencyMs + ' ms';
        window.animateResult(latencyBadge, 'fade-in');

        var items = data.results || [];
        resultsCount.textContent = items.length;

        // Add markers
        items.forEach(function (item) {
            var icon = item.type === 'branch' ? branchIcon : atmIcon;
            var marker = L.marker([item.lat, item.lng], { icon: icon }).addTo(map);
            var services = Array.isArray(item.services) ? item.services.join(', ') : (item.services || '');
            marker.bindPopup(
                '<strong>' + (item.name || item.id) + '</strong><br/>' +
                '<em>' + (item.type === 'branch' ? 'Branch' : 'ATM') + '</em><br/>' +
                (item.address || '') + '<br/>' +
                'Distance: ' + item.distance + ' km<br/>' +
                'Hours: ' + (item.hours || '') + '<br/>' +
                'Services: ' + services
            );
            resultMarkers.push(marker);
        });

        // Build results list
        var html = '';
        items.forEach(function (item) {
            var typeLabel = item.type === 'branch' ? 'Branch' : 'ATM';
            var services = Array.isArray(item.services) ? item.services : [];
            var badges = services.map(function (s) {
                return '<span class="geo-service-badge">' + s + '</span>';
            }).join('');

            html += '<div class="geo-result-item">' +
                '<div class="geo-result-header">' +
                '<span class="geo-result-name">' + typeLabel + ' ' + (item.name || item.id) + '</span>' +
                '<span class="geo-result-distance">' + item.distance + ' km</span>' +
                '</div>' +
                '<div class="geo-result-address">' + (item.address || '') + '</div>' +
                '<div class="geo-result-meta">' +
                '<span>' + (item.hours || '') + '</span>' +
                '<span class="geo-result-services">' + badges + '</span>' +
                '</div>' +
                '</div>';
        });

        window.renderAnimatedHtml(resultsList, html || '<div class="geo-no-results">No results found. Try increasing the radius.</div>', {
            containerClasses: 'fade-in',
            childSelector: '.geo-result-item',
            childClasses: 'slide-up highlight-new',
            staggerMs: 35
        });

        // Fit map bounds
        if (items.length > 0) {
            var allPoints = [[currentLat, currentLng]];
            items.forEach(function (item) { allPoints.push([item.lat, item.lng]); });
            lastBounds = allPoints;
            map.fitBounds(allPoints, { padding: [40, 40] });
        }
    }

    // Auto-search on load
    doSearch();
})();
