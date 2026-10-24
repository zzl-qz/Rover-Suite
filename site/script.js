(function () {
  var copyBtn = document.querySelector("#copy-cmd");
  var command = document.querySelector("#quick-start-cmd");
  if (copyBtn && command) {
    copyBtn.addEventListener("click", function () {
      var text = command.innerText.replace(/\n+$/, "");
      var markDone = function () {
        copyBtn.classList.add("is-done");
        copyBtn.setAttribute("aria-label", "已复制");
        window.setTimeout(function () {
          copyBtn.classList.remove("is-done");
          copyBtn.setAttribute("aria-label", "复制命令");
        }, 2000);
      };
      var fallback = function () {
        var area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.left = "-9999px";
        document.body.appendChild(area);
        area.select();
        document.execCommand("copy");
        area.remove();
        markDone();
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(markDone).catch(fallback);
      } else {
        fallback();
      }
    });
  }

  var toggle = document.querySelector(".nav-toggle");
  var nav = document.querySelector("#site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  var roverToggle = document.querySelector("#rover-toggle");
  var roverTip = document.querySelector("#rover-tip");
  var roverBot = document.querySelector("#rover-bot");
  if (roverToggle && roverTip && roverBot) {
    var drag = { on: false, moved: false, x: 0, y: 0, left: 0, top: 0 };

    roverToggle.addEventListener("pointerdown", function (event) {
      if (event.button !== 0) return;
      drag.on = true;
      drag.moved = false;
      drag.x = event.clientX;
      drag.y = event.clientY;
      var rect = roverBot.getBoundingClientRect();
      drag.left = rect.left;
      drag.top = rect.top;
      roverToggle.setPointerCapture(event.pointerId);
    });

    roverToggle.addEventListener("pointermove", function (event) {
      if (!drag.on) return;
      var dx = event.clientX - drag.x;
      var dy = event.clientY - drag.y;
      if (Math.abs(dx) > 4 || Math.abs(dy) > 4) drag.moved = true;
      if (!drag.moved) return;
      roverBot.classList.add("is-drag");
      var width = roverBot.offsetWidth;
      var height = roverBot.offsetHeight;
      var left = Math.min(window.innerWidth - width - 8, Math.max(8, drag.left + dx));
      var top = Math.min(window.innerHeight - height - 8, Math.max(8, drag.top + dy));
      roverBot.style.left = left + "px";
      roverBot.style.top = top + "px";
      roverBot.style.right = "auto";
      roverBot.style.bottom = "auto";
    });

    function endDrag() {
      drag.on = false;
      roverBot.classList.remove("is-drag");
    }

    roverToggle.addEventListener("pointerup", endDrag);
    roverToggle.addEventListener("pointercancel", endDrag);

    roverToggle.addEventListener("click", function () {
      if (drag.moved) {
        drag.moved = false;
        return;
      }
      var open = roverTip.hasAttribute("hidden");
      if (open) roverTip.removeAttribute("hidden");
      else roverTip.setAttribute("hidden", "");
      roverToggle.setAttribute("aria-expanded", open ? "true" : "false");
      roverBot.classList.remove("is-hit");
      void roverBot.offsetWidth;
      roverBot.classList.add("is-hit");
    });
  }

  var nodes = document.querySelectorAll(".reveal");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || !("IntersectionObserver" in window)) {
    nodes.forEach(function (el) { el.classList.add("is-in"); });
    return;
  }

  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-in");
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.12 });

  nodes.forEach(function (el) { observer.observe(el); });
})();
